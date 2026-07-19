import { DurableObject } from 'cloudflare:workers';
import { activeParticipants, expireParticipantReservations, participantIsActive } from './roomParticipants.js';

const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'content-type'
};
const ROOM_ID_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const DEFAULT_TTL_SECONDS = 12 * 60 * 60;
const DEFAULT_MAX_PARTICIPANTS = 12;
const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }];

export class ListeningPartyRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.env = env;
    this.sessions = new Map();
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = ws.deserializeAttachment();
      if (attachment?.participantId) this.sessions.set(attachment.participantId, ws);
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.method === 'POST' && url.pathname === '/internal/create') return this.create(request);
    if (request.method === 'POST' && url.pathname === '/internal/join') return this.join(request);
    if (request.method === 'GET' && url.pathname === '/internal/info') return this.info();
    if (request.method === 'GET' && url.pathname === '/socket') return this.connect(request);
    return json({ ok: false, error: 'Unknown room endpoint.' }, 404);
  }

  async create(request) {
    const payload = await readJson(request);
    const existing = await this.ctx.storage.get('room');
    if (existing && !existing.closed) return json({ ok: false, error: 'Room already exists.' }, 409);

    const now = Date.now();
    const host = participantRecord(payload.hostName || payload.name || 'Host', 'host', now);
    const room = {
      id: cleanRoomId(payload.roomId),
      createdAt: now,
      updatedAt: now,
      expiresAt: now + ttlSeconds(this.env) * 1000,
      closed: false,
      maxParticipants: maxParticipants(this.env),
      hostId: host.id,
      participants: { [host.id]: host },
      lastState: null
    };
    await this.ctx.storage.put('room', room);
    await this.ctx.storage.setAlarm(room.expiresAt);
    return json({ ok: true, data: { room: publicRoom(room, this.sessions), participant: privateParticipant(host) } });
  }

  async join(request) {
    const room = await this.activeRoom();
    if (!room) return json({ ok: false, error: 'Room not found.' }, 404);

    const now = Date.now();
    expireParticipantReservations(room, this.sessions, now);
    const activeCount = activeParticipants(room, this.sessions, now).length;
    if (activeCount >= room.maxParticipants) return json({ ok: false, error: 'Room is full.' }, 409);

    const payload = await readJson(request);
    const needsHost = !room.participants[room.hostId] || room.participants[room.hostId].leftAt;
    const participant = participantRecord(payload.name || 'Listener', needsHost ? 'host' : 'guest', now);
    room.participants[participant.id] = participant;
    if (needsHost) room.hostId = participant.id;
    room.updatedAt = now;
    await this.ctx.storage.put('room', room);
    return json({ ok: true, data: { room: publicRoom(room, this.sessions), participant: privateParticipant(participant) } });
  }

  async info() {
    const room = await this.activeRoom();
    if (!room) return json({ ok: false, error: 'Room not found.' }, 404);
    return json({ ok: true, data: { room: publicRoom(room, this.sessions) } });
  }

  async connect(request) {
    const room = await this.activeRoom();
    if (!room) return json({ ok: false, error: 'Room not found.' }, 404);
    if (request.headers.get('Upgrade') !== 'websocket') return json({ ok: false, error: 'Expected WebSocket upgrade.' }, 426);

    const url = new URL(request.url);
    const participantId = cleanId(url.searchParams.get('participantId'));
    const token = cleanToken(url.searchParams.get('token'));
    const participant = room.participants[participantId];
    if (!participant || participant.token !== token || !participantIsActive(participant, this.sessions)) {
      if (participant && !participant.leftAt) {
        participant.leftAt = Date.now();
        participant.lastSeenAt = participant.leftAt;
        await this.ctx.storage.put('room', room);
      }
      return json({ ok: false, error: 'Invalid participant credentials.' }, 401);
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.serializeAttachment({ participantId });
    this.ctx.acceptWebSocket(server, [participantId]);
    this.sessions.set(participantId, server);

    participant.connectedAt = Date.now();
    participant.lastSeenAt = participant.connectedAt;
    room.updatedAt = participant.connectedAt;
    await this.ctx.storage.put('room', room);

    this.send(server, {
      type: 'party:welcome',
      participantId,
      role: participant.role,
      room: publicRoom(room, this.sessions),
      peers: connectedPeers(room, this.sessions, participantId),
      iceServers: ICE_SERVERS,
      lastState: room.lastState
    });
    this.broadcast(room, { type: 'peer:joined', peer: publicParticipant(participant, true) }, participantId);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    const attachment = ws.deserializeAttachment();
    const participantId = attachment?.participantId;
    const room = await this.activeRoom();
    if (!room || !participantId || !room.participants[participantId]) return ws.close(1008, 'Room is unavailable.');

    const parsed = parseMessage(message);
    if (!parsed) return this.send(ws, { type: 'error', error: 'Invalid message.' });

    room.participants[participantId].lastSeenAt = Date.now();
    room.updatedAt = room.participants[participantId].lastSeenAt;
    await this.handleMessage(room, participantId, parsed);
  }

  async handleMessage(room, participantId, message) {
    if (message.type === 'party:ping') {
      this.sendTo(participantId, { type: 'party:pong', now: Date.now() });
      await this.ctx.storage.put('room', room);
      return;
    }

    if (message.type === 'signal') {
      this.sendTo(cleanId(message.to), {
        type: 'signal',
        from: participantId,
        kind: cleanText(message.kind, 32),
        data: message.data ?? null
      });
      await this.ctx.storage.put('room', room);
      return;
    }

    if (message.type === 'party:request') {
      this.sendTo(room.hostId, { type: 'party:request', from: participantId, payload: message.payload ?? {} });
      await this.ctx.storage.put('room', room);
      return;
    }

    const participant = room.participants[participantId];
    if (participant.role !== 'host') {
      this.sendTo(participantId, { type: 'error', error: 'Only the host can send that party message.' });
      await this.ctx.storage.put('room', room);
      return;
    }

    if (message.type === 'party:update') {
      room.lastState = { payload: message.payload ?? {}, updatedAt: Date.now(), hostId: participantId };
      this.broadcast(room, { type: 'party:update', from: participantId, payload: room.lastState.payload });
    } else if (message.type === 'party:host-transfer') {
      this.transferHost(room, participantId, cleanId(message.participantId));
    } else if (message.type === 'party:close') {
      await this.closeRoom(room, 'host_closed');
      return;
    } else {
      this.sendTo(participantId, { type: 'error', error: 'Unknown message type.' });
    }

    room.updatedAt = Date.now();
    await this.ctx.storage.put('room', room);
  }

  async webSocketClose(ws) {
    await this.disconnect(ws);
  }

  async webSocketError(ws) {
    await this.disconnect(ws);
  }

  async disconnect(ws) {
    const attachment = ws.deserializeAttachment();
    const participantId = attachment?.participantId;
    if (!participantId) return;
    this.sessions.delete(participantId);

    const room = await this.ctx.storage.get('room');
    const participant = room?.participants?.[participantId];
    if (!room || !participant) return;

    participant.lastSeenAt = Date.now();
    participant.leftAt = participant.lastSeenAt;
    participant.connectedAt = 0;
    room.updatedAt = participant.lastSeenAt;
    if (room.hostId === participantId) this.promoteConnectedHost(room, participantId);
    await this.ctx.storage.put('room', room);
    this.broadcast(room, { type: 'peer:left', participantId }, participantId);
  }

  async alarm() {
    const room = await this.ctx.storage.get('room');
    if (!room || room.closed || Date.now() >= room.expiresAt) {
      await this.ctx.storage.deleteAll();
      return;
    }
    await this.ctx.storage.setAlarm(room.expiresAt);
  }

  async activeRoom() {
    const room = await this.ctx.storage.get('room');
    if (!room || room.closed || Date.now() >= room.expiresAt) return null;
    return room;
  }

  transferHost(room, currentHostId, nextHostId) {
    if (!room.participants[nextHostId] || room.participants[nextHostId].leftAt) {
      this.sendTo(currentHostId, { type: 'error', error: 'That participant is not in the party.' });
      return;
    }
    room.participants[currentHostId].role = 'guest';
    room.participants[nextHostId].role = 'host';
    room.hostId = nextHostId;
    this.broadcast(room, { type: 'party:host-changed', hostId: nextHostId });
  }

  promoteConnectedHost(room, previousHostId) {
    const nextHostId = [...this.sessions.keys()].find((participantId) => {
      const participant = room.participants[participantId];
      return participantId !== previousHostId && participant && !participant.leftAt;
    });
    if (!nextHostId) return;
    room.participants[previousHostId].role = 'guest';
    room.participants[nextHostId].role = 'host';
    room.hostId = nextHostId;
    this.broadcast(room, { type: 'party:host-changed', hostId: nextHostId }, previousHostId);
  }

  async closeRoom(room, reason) {
    room.closed = true;
    room.updatedAt = Date.now();
    await this.ctx.storage.put('room', room);
    this.broadcast(room, { type: 'party:closed', reason });
    for (const ws of this.sessions.values()) ws.close(1000, reason);
    this.sessions.clear();
    await this.ctx.storage.deleteAlarm();
  }

  broadcast(room, payload, exceptId = '') {
    for (const participantId of Object.keys(room.participants)) {
      if (participantId !== exceptId) this.sendTo(participantId, payload);
    }
  }

  sendTo(participantId, payload) {
    const ws = this.sessions.get(participantId);
    if (ws) this.send(ws, payload);
  }

  send(ws, payload) {
    try {
      ws.send(JSON.stringify(payload));
    } catch {
      // Broken sockets are cleaned up by the close/error callbacks.
    }
  }
}

export default {
  async fetch(request, env) {
    const corsHeaders = { ...JSON_HEADERS, 'access-control-allow-origin': env.ALLOWED_ORIGIN || '*' };
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders });

    try {
      const url = new URL(request.url);
      if (url.pathname === '/health') return json({ ok: true, service: 'orchard-listening-party' }, 200, corsHeaders);
      if (request.method === 'POST' && url.pathname === '/rooms') return createRoom(request, env, url, corsHeaders);

      const match = url.pathname.match(/^\/rooms\/([A-Z2-9]+)(?:\/(join|socket))?$/);
      if (!match) return json({ ok: false, error: 'Unknown endpoint.' }, 404, corsHeaders);

      const roomId = cleanRoomId(match[1]);
      const stub = env.PARTY_ROOM.getByName(roomId);
      if (!match[2] && request.method === 'GET') {
        return withCors(await stub.fetch(internalRequest(url, '/internal/info')), corsHeaders, (data) => attachUrls(data, url.origin, roomId));
      }
      if (match[2] === 'join' && request.method === 'POST') {
        return withCors(await stub.fetch(internalRequest(url, '/internal/join', request)), corsHeaders, (data) => attachUrls(data, url.origin, roomId));
      }
      if (match[2] === 'socket' && request.method === 'GET') return stub.fetch(internalRequest(url, '/socket', request));
      return json({ ok: false, error: 'Method not allowed.' }, 405, corsHeaders);
    } catch (error) {
      return json({ ok: false, error: error.message || 'Request failed.' }, 500, corsHeaders);
    }
  }
};

async function createRoom(request, env, url, corsHeaders) {
  const roomId = randomRoomId();
  const payload = { ...await readJson(request), roomId };
  const stub = env.PARTY_ROOM.getByName(roomId);
  const response = await stub.fetch(new Request(new URL('/internal/create', url), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload)
  }));
  return withCors(response, corsHeaders, (data) => attachUrls(data, url.origin, roomId));
}

function attachUrls(data, origin, roomId) {
  const participant = data?.data?.participant;
  if (!participant) return data;
  data.data.room.socketUrl = `${origin.replace(/^http/, 'ws')}/rooms/${roomId}/socket`;
  data.data.room.joinUrl = `${origin}/rooms/${roomId}/join`;
  data.data.room.shareUrl = `${origin}/rooms/${roomId}`;
  return data;
}

function internalRequest(url, pathname, request) {
  const internalUrl = new URL(pathname, url);
  internalUrl.search = url.search;
  if (!request) return new Request(internalUrl, { method: 'GET' });
  return new Request(internalUrl, {
    method: request.method,
    headers: request.headers,
    body: request.body
  });
}

async function withCors(response, headers, transform) {
  const body = await response.json().catch(() => null);
  const data = transform && body ? transform(body) : body;
  return json(data ?? { ok: false, error: 'Invalid room response.' }, response.status, headers);
}

function participantRecord(name, role, now) {
  return {
    id: randomToken(12),
    token: randomToken(32),
    name: cleanText(name, 48) || (role === 'host' ? 'Host' : 'Listener'),
    role,
    joinedAt: now,
    connectedAt: 0,
    lastSeenAt: now,
    leftAt: 0
  };
}

function publicRoom(room, sessions) {
  return {
    id: room.id,
    createdAt: room.createdAt,
    expiresAt: room.expiresAt,
    closed: room.closed,
    hostId: room.hostId,
    maxParticipants: room.maxParticipants,
    participantCount: activeParticipants(room, sessions).length,
    peers: connectedPeers(room, sessions)
  };
}

function connectedPeers(room, sessions, exceptId = '') {
  return activeParticipants(room, sessions)
    .filter((peer) => peer.id !== exceptId)
    .map((peer) => publicParticipant(peer, sessions.has(peer.id)));
}

function publicParticipant(peer, online) {
  return { id: peer.id, name: peer.name, role: peer.role, joinedAt: peer.joinedAt, online };
}

function privateParticipant(peer) {
  return { ...publicParticipant(peer, false), token: peer.token };
}

async function readJson(request) {
  return request.headers.get('content-type')?.includes('application/json')
    ? await request.json().catch(() => ({}))
    : {};
}

function parseMessage(message) {
  if (typeof message !== 'string' || message.length > 65536) return null;
  try {
    const parsed = JSON.parse(message);
    return parsed && typeof parsed.type === 'string' ? parsed : null;
  } catch {
    return null;
  }
}

function json(data, status = 200, headers = JSON_HEADERS) {
  return new Response(JSON.stringify(data), { status, headers });
}

function randomRoomId() {
  const bytes = new Uint8Array(6);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => ROOM_ID_ALPHABET[byte % ROOM_ID_ALPHABET.length]).join('');
}

function randomToken(length) {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function cleanRoomId(value) {
  return cleanText(value, 16).toUpperCase().replace(/[^A-Z2-9]/g, '');
}

function cleanId(value) {
  return cleanText(value, 64).replace(/[^a-f0-9]/g, '');
}

function cleanToken(value) {
  return cleanText(value, 128).replace(/[^a-f0-9]/g, '');
}

function cleanText(value, maxLength) {
  return String(value || '').trim().replace(/\s+/g, ' ').slice(0, maxLength);
}

function ttlSeconds(env) {
  const value = Number(env.ROOM_TTL_SECONDS);
  return Number.isFinite(value) && value > 60 ? value : DEFAULT_TTL_SECONDS;
}

function maxParticipants(env) {
  const value = Number(env.MAX_PARTICIPANTS);
  return Number.isFinite(value) && value >= 2 && value <= 50 ? value : DEFAULT_MAX_PARTICIPANTS;
}
