// Hosts the intentionally LAN-visible Orchard Connect endpoint. Pairing tokens
// authorize first contact; persisted device tokens are stored only as hashes.
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import path from 'node:path';
import QRCode from 'qrcode';
import { connectClientJs, connectCss, connectHtml } from './orchardConnectPage.js';

const preferredConnectPort = 32145;

function localLanAddress() {
  for (const entries of Object.values(networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) return entry.address;
    }
  }
  return '127.0.0.1';
}

function jsonReply(reply, data) {
  if (typeof reply === 'function') reply({ ok: true, data });
}

function notFound(res) {
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Not found');
}

function deviceTokenHash(token = '') {
  return createHash('sha256').update(String(token)).digest('hex');
}

function publicDevice(device) {
  return {
    id: device.id,
    name: device.name,
    connected: Boolean(device.connected),
    pairedAt: device.pairedAt,
    lastSeenAt: device.lastSeenAt
  };
}

function publicDevices(devices) {
  return [...devices.values()].map(publicDevice);
}

function publicPendingRequest(request) {
  return {
    id: request.id,
    name: request.name,
    createdAt: request.createdAt
  };
}

function publicPendingRequests(pending) {
  return [...pending.values()].map(publicPendingRequest);
}

function storedDevice(device) {
  return {
    id: device.id,
    tokenHash: device.tokenHash,
    name: device.name,
    pairedAt: device.pairedAt,
    lastSeenAt: device.lastSeenAt
  };
}

function importedStoredDevices(payload = {}) {
  const imported = Array.isArray(payload.devices) ? payload.devices : [];
  return imported
    .filter((device) => device?.id && /^[a-f0-9]{64}$/i.test(String(device.tokenHash || '')))
    .map((device) => ({
      id: String(device.id).slice(0, 80),
      tokenHash: String(device.tokenHash),
      name: String(device.name || 'Phone').slice(0, 60),
      connected: false,
      pairedAt: Number(device.pairedAt) || Date.now(),
      lastSeenAt: Number(device.lastSeenAt) || 0
    }));
}

async function loadStoredDevices(deviceStorePath) {
  if (!deviceStorePath) return [];

  try {
    const text = await fs.readFile(deviceStorePath, 'utf8');
    const data = JSON.parse(text);
    return (Array.isArray(data.devices) ? data.devices : [])
      .filter((device) => device?.id && device?.tokenHash)
      .map((device) => ({
        id: String(device.id),
        tokenHash: String(device.tokenHash),
        name: String(device.name || 'Phone').slice(0, 60),
        connected: false,
        pairedAt: Number(device.pairedAt) || Date.now(),
        lastSeenAt: Number(device.lastSeenAt) || 0
      }));
  } catch (error) {
    if (error?.code !== 'ENOENT') console.warn(`Could not load Orchard Connect devices: ${error.message}`);
    return [];
  }
}

async function saveStoredDevices(deviceStorePath, devices) {
  if (!deviceStorePath) return;

  const tmpPath = `${deviceStorePath}.tmp`;
  const data = {
    version: 1,
    devices: [...devices.values()].map(storedDevice)
  };
  try {
    await fs.mkdir(path.dirname(deviceStorePath), { recursive: true });
    await fs.writeFile(tmpPath, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
    await fs.rename(tmpPath, deviceStorePath);
  } catch (error) {
    console.warn(`Could not save Orchard Connect devices: ${error.message}`);
  }
}

async function listenOnPreferredPort(server) {
  function listen(port) {
    return new Promise((resolve, reject) => {
      function onError(error) {
        server.off('listening', onListening);
        reject(error);
      }

      function onListening() {
        server.off('error', onError);
        resolve();
      }

      server.once('error', onError);
      server.once('listening', onListening);
      server.listen(port, '0.0.0.0');
    });
  }

  try {
    await listen(preferredConnectPort);
  } catch (error) {
    if (error?.code !== 'EADDRINUSE' && error?.code !== 'EACCES') throw error;
    await listen(0);
  }
}

/**
 * Starts the paired-device LAN service and links it to the desktop-only bridge.
 * Pairing and device tokens are owned here; callers must invoke `close()` before
 * releasing the main process.
 */
export async function createOrchardConnectServer({ Server, desktopIo, deviceStorePath }) {
  const pairings = new Map();
  const pending = new Map();
  const devices = new Map((await loadStoredDevices(deviceStorePath)).map((device) => [device.id, device]));
  const remoteSockets = new Map();
  let deviceSavePromise = Promise.resolve();
  let currentState = { status: 'idle' };

  const httpServer = createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const headers = { 'Cache-Control': 'no-store' };

    if (url.pathname.startsWith('/socket.io/')) {
      return;
    }

    if (url.pathname === '/connect-info') {
      res.writeHead(200, {
        ...headers,
        'Access-Control-Allow-Origin': '*',
        'Content-Type': 'application/json; charset=utf-8'
      });
      res.end(JSON.stringify({
        name: 'Orchard Connect',
        serverUrl: baseUrl(),
        port: httpServer.address().port,
        preferredPort: preferredConnectPort,
        stable: httpServer.address().port === preferredConnectPort
      }));
    } else if (url.pathname === '/' || url.pathname === '/connect') {
      res.writeHead(200, { ...headers, 'Content-Type': 'text/html; charset=utf-8' });
      res.end(connectHtml);
    } else if (url.pathname === '/connect/client.js') {
      res.writeHead(200, { ...headers, 'Content-Type': 'text/javascript; charset=utf-8' });
      res.end(connectClientJs);
    } else if (url.pathname === '/connect/style.css') {
      res.writeHead(200, { ...headers, 'Content-Type': 'text/css; charset=utf-8' });
      res.end(connectCss);
    } else {
      notFound(res);
    }
  });

  const remoteIo = new Server(httpServer, { cors: { origin: '*' } });
  await listenOnPreferredPort(httpServer);

  function baseUrl() {
    return `http://${localLanAddress()}:${httpServer.address().port}`;
  }

  async function newPairing() {
    const token = randomBytes(18).toString('base64url');
    const serverUrl = baseUrl();
    const webUrl = `${serverUrl}/connect?token=${encodeURIComponent(token)}`;
    const appUrl = `orchard-connect://pair?server=${encodeURIComponent(serverUrl)}&token=${encodeURIComponent(token)}`;
    const pairing = {
      token,
      url: appUrl,
      appUrl,
      webUrl,
      qrSvg: await QRCode.toString(appUrl, { type: 'svg', margin: 1, width: 168 }),
      expiresAt: Date.now() + 10 * 60 * 1000
    };
    pairings.set(token, pairing);
    return pairing;
  }

  async function pairingInfo() {
    const active = [...pairings.values()].find((item) => item.expiresAt > Date.now()) || await newPairing();
    return {
      serverUrl: baseUrl(),
      pairUrl: active.appUrl,
      appPairUrl: active.appUrl,
      webPairUrl: active.webUrl,
      qrSvg: active.qrSvg,
      expiresAt: active.expiresAt,
      pending: publicPendingRequests(pending),
      devices: publicDevices(devices)
    };
  }

  function emitPairingState() {
    desktopIo.emit('connect:pairing-state', {
      pending: publicPendingRequests(pending),
      devices: publicDevices(devices)
    });
  }

  function queueDeviceSave() {
    deviceSavePromise = deviceSavePromise
      .catch(() => {})
      .then(() => saveStoredDevices(deviceStorePath, devices));
  }

  function approvePairing(pairingId) {
    const request = pending.get(pairingId);
    if (!request) return null;
    const socket = remoteIo.sockets.sockets.get(request.socketId);
    if (!socket) {
      pending.delete(pairingId);
      emitPairingState();
      return null;
    }

    const device = {
      id: randomUUID(),
      tokenHash: '',
      name: request.name,
      connected: true,
      pairedAt: Date.now(),
      lastSeenAt: Date.now()
    };
    const deviceToken = request.proposedDeviceToken || randomBytes(24).toString('base64url');
    device.tokenHash = deviceTokenHash(deviceToken);
    pending.delete(pairingId);
    if (request.token) pairings.delete(request.token);
    devices.set(device.id, device);
    remoteSockets.set(socket.id, device.id);
    socket.join('paired');
    socket.emit('connect:approved', { deviceToken, state: currentState });
    emitPairingState();
    queueDeviceSave();
    return publicDevice(device);
  }

  function rejectPairing(pairingId) {
    const request = pending.get(pairingId);
    if (!request) return false;
    pending.delete(pairingId);
    if (request.token) pairings.delete(request.token);
    remoteIo.sockets.sockets.get(request.socketId)?.emit('connect:rejected');
    remoteIo.sockets.sockets.get(request.socketId)?.disconnect(true);
    emitPairingState();
    return true;
  }

  function revokeDevice(deviceId) {
    const device = devices.get(deviceId);
    if (!device) return false;
    devices.delete(deviceId);
    for (const [socketId, pairedDeviceId] of remoteSockets.entries()) {
      if (pairedDeviceId !== deviceId) continue;
      remoteSockets.delete(socketId);
      remoteIo.sockets.sockets.get(socketId)?.emit('connect:revoked');
      remoteIo.sockets.sockets.get(socketId)?.disconnect(true);
    }
    emitPairingState();
    queueDeviceSave();
    return true;
  }

  function exportDevices() {
    return {
      version: 1,
      devices: [...devices.values()].map(storedDevice)
    };
  }

  function importDevices(payload) {
    const imported = importedStoredDevices(payload);
    for (const device of imported) devices.set(device.id, device);
    emitPairingState();
    queueDeviceSave();
    return publicDevices(devices);
  }

  remoteIo.on('connection', (socket) => {
    socket.on('connect:hello', ({ token = '', deviceToken = '', name = '' } = {}, reply) => {
      const existing = deviceToken
        ? [...devices.values()].find((device) => device.tokenHash === deviceTokenHash(deviceToken))
        : null;
      if (existing) {
        existing.connected = true;
        existing.lastSeenAt = Date.now();
        remoteSockets.set(socket.id, existing.id);
        socket.join('paired');
        emitPairingState();
        queueDeviceSave();
        jsonReply(reply, { status: 'approved', state: currentState });
        return;
      }

      const pairing = pairings.get(token);
      if (!pairing || pairing.expiresAt < Date.now()) {
        jsonReply(reply, { status: 'expired' });
        return;
      }

      const pendingRequest = [...pending.values()].find((request) => request.token === token);
      if (pendingRequest) {
        const previousSocketId = pendingRequest.socketId;
        pendingRequest.socketId = socket.id;
        pendingRequest.name = String(name || pendingRequest.name || 'Phone').slice(0, 60);
        pendingRequest.proposedDeviceToken = String(deviceToken || pendingRequest.proposedDeviceToken || '').slice(0, 128);
        if (previousSocketId !== socket.id) remoteIo.sockets.sockets.get(previousSocketId)?.disconnect(true);
        desktopIo.emit('connect:pairing-request', publicPendingRequest(pendingRequest));
        emitPairingState();
        jsonReply(reply, { status: 'pending' });
        return;
      }

      const request = {
        id: randomUUID(),
        token,
        proposedDeviceToken: String(deviceToken || '').slice(0, 128),
        name: String(name || 'Phone').slice(0, 60),
        createdAt: Date.now(),
        socketId: socket.id
      };
      pending.set(request.id, request);
      desktopIo.emit('connect:pairing-request', publicPendingRequest(request));
      emitPairingState();
      jsonReply(reply, { status: 'pending' });
    });

    socket.on('connect:command', (command = {}) => {
      const deviceId = remoteSockets.get(socket.id);
      if (!deviceId || !devices.has(deviceId)) return;
      desktopIo.emit('connect:remote-command', { deviceId, command });
    });

    socket.on('connect:search', ({ query = '', requestId = '' } = {}) => {
      const deviceId = remoteSockets.get(socket.id);
      if (!deviceId || !devices.has(deviceId)) return;
      desktopIo.emit('connect:remote-search', { deviceId, query, requestId });
    });

    socket.on('connect:library', ({ requestId = '' } = {}) => {
      const deviceId = remoteSockets.get(socket.id);
      if (!deviceId || !devices.has(deviceId)) return;
      desktopIo.emit('connect:remote-library', { deviceId, requestId });
    });

    socket.on('disconnect', () => {
      const deviceId = remoteSockets.get(socket.id);
      remoteSockets.delete(socket.id);
      for (const [id, request] of pending.entries()) {
        if (request.socketId === socket.id) pending.delete(id);
      }
      if (deviceId && devices.has(deviceId)) {
        const device = devices.get(deviceId);
        device.connected = [...remoteSockets.values()].includes(deviceId);
        device.lastSeenAt = Date.now();
        queueDeviceSave();
      }
      emitPairingState();
    });
  });

  return {
    async registerDesktop(socket) {
      socket.on('connect:pairing-info', async (_payload, reply) => jsonReply(reply, await pairingInfo()));
      socket.on('connect:pairing-refresh', async (_payload, reply) => jsonReply(reply, await newPairing()));
      socket.on('connect:pairing-approve', ({ id } = {}, reply) => jsonReply(reply, { device: approvePairing(id) }));
      socket.on('connect:pairing-reject', ({ id } = {}, reply) => jsonReply(reply, { rejected: rejectPairing(id) }));
      socket.on('connect:device-revoke', ({ id } = {}, reply) => jsonReply(reply, { revoked: revokeDevice(id) }));
      socket.on('connect:devices-export', (_payload, reply) => jsonReply(reply, exportDevices()));
      socket.on('connect:devices-import', (payload, reply) => jsonReply(reply, { devices: importDevices(payload) }));
      socket.on('connect:desktop-state', (state = {}) => {
        currentState = state;
        remoteIo.to('paired').emit('connect:state', currentState);
      });
      socket.on('connect:remote-search-results', ({ deviceId, ...payload } = {}) => {
        for (const [socketId, pairedDeviceId] of remoteSockets.entries()) {
          if (pairedDeviceId === deviceId) remoteIo.to(socketId).emit('connect:search-results', payload);
        }
      });
      socket.on('connect:remote-library-results', ({ deviceId, ...payload } = {}) => {
        for (const [socketId, pairedDeviceId] of remoteSockets.entries()) {
          if (pairedDeviceId === deviceId) remoteIo.to(socketId).emit('connect:library-results', payload);
        }
      });
    },
    close() {
      // Socket.IO closes active LAN clients before the underlying listener is released.
      void deviceSavePromise;
      remoteIo.close();
      httpServer.close();
    }
  };
}
