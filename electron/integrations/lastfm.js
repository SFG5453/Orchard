// Keeps Last.fm sessions encrypted in the main process and routes signed API
// operations through the Worker so Orchard never bundles Last.fm credentials.
import { readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const require = createRequire(import.meta.url);
const { LastFmNode } = require('lastfm');
const { LASTFM } = IPC_CHANNELS;

const defaultWorkerEndpoint = 'https://lastfm.sfg545.dev';
const sessionFilename = 'lastfm-session.json';
const lastfmClient = new LastFmNode({ useragent: 'OrchardDesktop/3.0' });

function cleanText(value, maxLength = 500) {
  return String(value || '').trim().replace(/\s+/g, ' ').slice(0, maxLength);
}

export function normalizeLastfmTrack(value) {
  const title = cleanText(value?.title);
  const artist = cleanText(value?.artist);
  if (!title || !artist) return null;
  return {
    title,
    artist,
    album: cleanText(value?.album),
    albumArtist: cleanText(value?.albumArtist),
    duration: Math.max(0, Math.min(86_400, Math.round(Number(value?.duration) || 0)))
  };
}

export function setupLastfm({ app, ipcMain, net, safeStorage, shell }) {
  const workerEndpoint = cleanText(process.env.ORCHARD_LASTFM_WORKER_URL, 2_000) || defaultWorkerEndpoint;
  const sessionPath = path.join(app.getPath('userData'), sessionFilename);
  let record = null;
  let session = null;
  let pending = null;
  let loaded = false;

  const canPersistSecurely = () => {
    if (!safeStorage.isEncryptionAvailable()) return false;
    return safeStorage.getSelectedStorageBackend?.() !== 'basic_text';
  };

  const makeSession = (value) => {
    const candidate = lastfmClient.session({ user: value?.user, key: value?.key });
    return candidate.isAuthorised() && candidate.user ? candidate : null;
  };

  const loadRecord = async () => {
    if (loaded) return;
    loaded = true;
    if (!canPersistSecurely()) return;
    try {
      const stored = JSON.parse(await readFile(sessionPath, 'utf8'));
      record = JSON.parse(safeStorage.decryptString(Buffer.from(stored.encrypted, 'base64')));
      session = makeSession(record);
      if (!session) record = null;
    } catch {
      record = null;
      session = null;
    }
  };

  const persistRecord = async () => {
    if (!record || !canPersistSecurely()) return;
    const encrypted = safeStorage.encryptString(JSON.stringify(record)).toString('base64');
    await writeFile(sessionPath, `${JSON.stringify({ encrypted })}\n`, { mode: 0o600 });
  };

  const forgetRecord = async () => {
    record = null;
    session = null;
    pending = null;
    if (canPersistSecurely()) {
      await writeFile(sessionPath, '{}\n', { mode: 0o600 }).catch(() => {});
    }
  };

  const publicState = () => {
    if (pending) return { status: 'pending', expiresAt: pending.expiresAt, secureStorage: canPersistSecurely() };
    if (session) return { status: 'connected', user: session.user, secureStorage: canPersistSecurely() };
    return { status: 'disconnected', secureStorage: canPersistSecurely() };
  };

  const workerRequest = async (route, input) => {
    const response = await net.fetch(new URL(route, workerEndpoint), {
      method: 'POST',
      headers: { accept: 'application/json', 'content-type': 'application/json' },
      body: JSON.stringify(input || {})
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(payload.error || `Last.fm request failed (${response.status}).`);
      error.status = response.status;
      throw error;
    }
    return payload;
  };

  const requireSession = async () => {
    await loadRecord();
    if (!session) throw new Error('Connect a Last.fm account before scrobbling.');
    return session;
  };

  const submitTrack = async (route, input) => {
    const activeSession = await requireSession();
    const track = normalizeLastfmTrack(input?.track);
    if (!track) throw new Error('Track title and artist are required for Last.fm.');
    try {
      return await workerRequest(route, {
        sessionKey: activeSession.key,
        track,
        ...(route === '/scrobble' ? { timestamp: Math.floor(Number(input?.timestamp)) } : {})
      });
    } catch (error) {
      if (error.status === 401) await forgetRecord();
      throw error;
    }
  };

  ipcMain.handle(LASTFM.STATUS, async () => {
    await loadRecord();
    return publicState();
  });

  ipcMain.handle(LASTFM.CONNECT, async () => {
    await loadRecord();
    const authorization = await workerRequest('/auth/token');
    pending = {
      token: cleanText(authorization.token, 512),
      expiresAt: Date.now() + 10 * 60 * 1000
    };
    const authorizationUrl = new URL(authorization.authorizationUrl);
    if (authorizationUrl.protocol !== 'https:' || authorizationUrl.hostname !== 'www.last.fm') {
      pending = null;
      throw new Error('The Last.fm Worker returned an invalid authorization URL.');
    }
    await shell.openExternal(authorizationUrl.toString());
    return publicState();
  });

  ipcMain.handle(LASTFM.COMPLETE, async () => {
    if (!pending?.token || Date.now() >= pending.expiresAt) {
      pending = null;
      throw new Error('Start Last.fm connection again; the authorization request expired.');
    }
    const authorized = await workerRequest('/auth/session', { token: pending.token });
    record = { user: cleanText(authorized.user, 100), key: cleanText(authorized.sessionKey, 512) };
    session = makeSession(record);
    if (!session) throw new Error('Last.fm returned an invalid session.');
    pending = null;
    await persistRecord().catch(() => {});
    return publicState();
  });

  ipcMain.handle(LASTFM.DISCONNECT, async () => {
    await forgetRecord();
    return publicState();
  });
  ipcMain.handle(LASTFM.NOW_PLAYING, (_event, input) => submitTrack('/now-playing', input));
  ipcMain.handle(LASTFM.SCROBBLE, (_event, input) => submitTrack('/scrobble', input));
}
