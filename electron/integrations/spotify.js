// Handles Spotify authentication, sp_dc session cookie storage, access token generation,
// and searching/fetching Spotify Canvas animated artwork videos (.mp4).
import { readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const require = createRequire(import.meta.url);
const { BrowserWindow, session: electronSession } = require('electron');
const { SPOTIFY } = IPC_CHANNELS;

const sessionFilename = 'spotify-session.json';
// Login and token harvesting share one partition so the web player sees a normal,
// persistent browser profile rather than a fresh jar on every call.
const sessionPartition = 'persist:orchard-spotify';
const tokenHarvestTimeoutMs = 25_000;
const userAgent = 'Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)';
const browserUserAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0';

function cleanText(value, maxLength = 500) {
  return String(value || '').trim().replace(/\s+/g, ' ').slice(0, maxLength);
}

/**
 * Normalizes sp_dc string from raw input or full cookie string.
 */
export function extractSpdc(cookieInput = '') {
  const str = String(cookieInput || '').trim();
  if (!str) return '';
  if (!str.includes('=')) return str; // raw value
  const match = str.match(/(?:^|;\s*)sp_dc=([^;]+)/);
  return match ? match[1].trim() : '';
}

export function setupSpotify({ app, ipcMain, net, safeStorage }) {
  const sessionPath = path.join(app.getPath('userData'), sessionFilename);
  let spdcToken = '';
  let cachedAccessToken = null;
  let cachedClientToken = null;
  let pendingAccessToken = null;
  let loaded = false;
  // Spotify throttles these lookups, so never ask twice for the same track --
  // including misses, which are the common case since most tracks have no canvas.
  const canvasCache = new Map();

  const canPersistSecurely = () => {
    if (!safeStorage || !safeStorage.isEncryptionAvailable()) return false;
    return safeStorage.getSelectedStorageBackend?.() !== 'basic_text';
  };

  const loadRecord = async () => {
    if (loaded) return;
    loaded = true;
    try {
      const content = await readFile(sessionPath, 'utf8');
      if (canPersistSecurely()) {
        const stored = JSON.parse(content);
        spdcToken = safeStorage.decryptString(Buffer.from(stored.encrypted, 'base64'));
      } else {
        const stored = JSON.parse(content);
        spdcToken = stored.spdc || '';
      }
    } catch {
      spdcToken = '';
    }
  };

  const saveRecord = async (spdc) => {
    spdcToken = extractSpdc(spdc);
    cachedAccessToken = null;
    cachedClientToken = null;
    if (!spdcToken) {
      try {
        await writeFile(sessionPath, JSON.stringify({ spdc: '' }), 'utf8');
      } catch {}
      return;
    }

    try {
      if (canPersistSecurely()) {
        const encrypted = safeStorage.encryptString(spdcToken).toString('base64');
        await writeFile(sessionPath, JSON.stringify({ encrypted }), 'utf8');
      } else {
        await writeFile(sessionPath, JSON.stringify({ spdc: spdcToken }), 'utf8');
      }
    } catch (err) {
      console.warn('Failed to save Spotify session', err);
    }
  };

  const clearRecord = async () => {
    await saveRecord('');
    // Otherwise the partition keeps a fully logged-in Spotify profile after disconnect.
    try {
      await electronSession.fromPartition(sessionPartition).clearStorageData({
        storages: ['cookies', 'localstorage', 'indexdb']
      });
    } catch (err) {
      console.warn('Failed to clear Spotify session storage', err);
    }
  };

  const getStatus = async () => {
    await loadRecord();
    return {
      status: spdcToken ? 'connected' : 'disconnected',
      hasSpdc: Boolean(spdcToken),
      secureStorage: canPersistSecurely()
    };
  };

  const spotifySession = () => electronSession.fromPartition(sessionPartition);

  // Plants the stored sp_dc into the shared partition so an offscreen web player
  // loads already logged in.
  const primeSessionCookie = async () => {
    if (!spdcToken) return;
    try {
      await spotifySession().cookies.set({
        url: 'https://open.spotify.com',
        name: 'sp_dc',
        value: spdcToken,
        domain: '.spotify.com',
        path: '/',
        secure: true,
        httpOnly: true,
        expirationDate: Math.floor(Date.now() / 1000) + 31_536_000
      });
    } catch (err) {
      console.warn('Failed to prime Spotify session cookie', err);
    }
  };

  // Spotify retired the old get_access_token endpoint, and its replacement
  // (/api/token) rejects requests that are not signed the way its own web player
  // signs them. So rather than forge that signature we load the real web player
  // offscreen and read the token it obtains for itself, off the wire via CDP.
  const harvestAccessToken = async () => {
    await primeSessionCookie();

    return new Promise((resolve) => {
      let settled = false;
      let timer = null;
      const pendingRequests = new Set();

      const win = new BrowserWindow({
        show: false,
        webPreferences: {
          partition: sessionPartition,
          nodeIntegration: false,
          contextIsolation: true
        }
      });
      win.webContents.userAgent = browserUserAgent;

      const finish = (token) => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        try {
          if (win.webContents.debugger.isAttached()) win.webContents.debugger.detach();
        } catch {}
        if (!win.isDestroyed()) win.destroy();
        resolve(token || null);
      };

      timer = setTimeout(() => finish(null), tokenHarvestTimeoutMs);

      try {
        win.webContents.debugger.attach('1.3');
      } catch (err) {
        console.warn('Failed to attach Spotify token debugger', err);
        finish(null);
        return;
      }

      win.webContents.debugger.on('message', async (_event, method, params) => {
        try {
          if (method === 'Network.responseReceived') {
            if (/\/api\/token(?:\?|$)/.test(params.response?.url || '')) {
              pendingRequests.add(params.requestId);
            }
            return;
          }

          if (method !== 'Network.loadingFinished') return;
          if (!pendingRequests.has(params.requestId)) return;
          pendingRequests.delete(params.requestId);

          const { body, base64Encoded } = await win.webContents.debugger.sendCommand(
            'Network.getResponseBody',
            { requestId: params.requestId }
          );
          const raw = base64Encoded ? Buffer.from(body, 'base64').toString('utf8') : body;
          const data = JSON.parse(raw);
          // The player also mints an anonymous token before the cookie is applied;
          // that one cannot read canvases, so keep waiting for the logged-in one.
          if (data?.accessToken && data.isAnonymous !== true) {
            cachedAccessToken = {
              token: data.accessToken,
              expiresAt: data.accessTokenExpirationTimestampMs || (Date.now() + 3600_000)
            };
            finish(data.accessToken);
          }
        } catch (err) {
          console.warn('Failed to read Spotify token response', err);
        }
      });

      win.webContents.debugger.sendCommand('Network.enable').catch(() => {});
      win.webContents.on('render-process-gone', () => finish(null));

      win.webContents.on('did-fail-load', (_evt, errorCode, errorDescription, validatedURL, isMainFrame) => {
        if (!isMainFrame) return;
        console.warn('Spotify web player load failed', { errorCode, errorDescription, validatedURL });
      });

      // loadURL rejects whenever the page's own navigation supersedes ours
      // (redirects, service worker takeover, client-side routing). That is normal
      // here, so let the debugger keep listening and let the timeout decide.
      win.loadURL('https://open.spotify.com/', { userAgent: browserUserAgent }).catch((err) => {
        console.warn('Spotify web player navigation interrupted (continuing)', err?.code || err);
      });
    });
  };

  // Helper to fetch Spotify Web Access Token using sp_dc cookie
  const getAccessToken = async () => {
    if (!spdcToken) return null;
    if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) {
      return cachedAccessToken.token;
    }
    if (!pendingAccessToken) {
      pendingAccessToken = harvestAccessToken().finally(() => {
        pendingAccessToken = null;
      });
    }
    return pendingAccessToken;
  };

  // Helper to fetch Spotify Client Token
  const getClientToken = async () => {
    if (cachedClientToken && cachedClientToken.expiresAt > Date.now() + 60_000) {
      return cachedClientToken.token;
    }

    try {
      const response = await net.fetch('https://clienttoken.spotify.com/v1/clienttoken', {
        method: 'POST',
        headers: {
          'Accept': 'application/json',
          'Content-Type': 'application/json',
          'User-Agent': browserUserAgent
        },
        body: JSON.stringify({
          client_data: {
            // clienttoken rejects the request with a bare 400 if client_version is absent.
            client_version: '1.2.46.25.g7f0cbf22',
            client_id: 'd8a5ed958d274c2e8ee717e6a4b0971d',
            js_sdk_data: {
              device_brand: 'Apple',
              device_model: 'Macintosh',
              os: 'macOS',
              os_version: '10.15.7'
            }
          }
        })
      });
      if (!response.ok) return null;
      const data = await response.json();
      const clientToken = data?.granted_token?.token;
      if (clientToken) {
        cachedClientToken = {
          token: clientToken,
          expiresAt: Date.now() + 7200_000
        };
        return clientToken;
      }
    } catch (err) {
      console.warn('Failed to fetch Spotify client token', err);
    }
    return null;
  };

  // The query the web player itself runs. Preferred over the public Web API,
  // which throttles web-player tokens aggressively.
  const searchViaPathfinder = async (searchTerm, accessToken, clientToken) => {
    try {
      const variables = JSON.stringify({
        searchTerm,
        offset: 0,
        limit: 10,
        numberOfTopResults: 5,
        includeAudiobooks: false,
        includePreReleases: false
      });
      const extensions = JSON.stringify({
        persistedQuery: {
          version: 1,
          sha256Hash: 'bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428'
        }
      });
      const url = `https://api-partner.spotify.com/pathfinder/v1/query?operationName=searchTracks&variables=${encodeURIComponent(variables)}&extensions=${encodeURIComponent(extensions)}`;
      const response = await net.fetch(url, {
        headers: {
          'Accept': 'application/json',
          'Authorization': `Bearer ${accessToken}`,
          // Pathfinder rejects requests that carry no client token.
          'Client-Token': clientToken || '',
          'App-platform': 'WebPlayer',
          'User-Agent': browserUserAgent
        }
      });
      if (!response.ok) {
        console.warn('Spotify pathfinder search rejected', response.status);
        return null;
      }

      const data = await response.json();
      const firstItem = (data?.data?.searchV2?.tracksV2?.items || [])[0]?.item?.data;
      if (firstItem?.id) return firstItem.id;
      if (firstItem?.uri) return firstItem.uri.split(':').pop();
    } catch (err) {
      console.warn('Spotify pathfinder search failed', err);
    }
    return null;
  };

  // Searches Spotify for a track ID matching artist & title
  const searchSpotifyTrackId = async (accessToken, title, artist, clientToken) => {
    if (!accessToken || !title) return null;
    const searchTerm = `${title} ${artist}`.trim();

    const pathfinderId = await searchViaPathfinder(searchTerm, accessToken, clientToken);
    if (pathfinderId) return pathfinderId;

    // Fallback only. Web-player tokens are rate limited hard here (429), so this
    // must not run first or it burns the quota before pathfinder is tried.
    try {
      const query = encodeURIComponent(searchTerm);
      const response = await net.fetch(`https://api.spotify.com/v1/search?q=${query}&type=track&limit=5`, {
        headers: {
          'Authorization': `Bearer ${accessToken}`,
          'User-Agent': browserUserAgent
        }
      });
      if (!response.ok) {
        console.warn('Spotify web api search rejected', response.status);
      }
      if (response.ok) {
        const data = await response.json();
        const items = data?.tracks?.items || [];
        if (items.length) {
          const normalizedTitle = title.toLowerCase().trim();
          const match = items.find((t) => {
            const tTitle = (t.name || '').toLowerCase().trim();
            return tTitle === normalizedTitle || tTitle.includes(normalizedTitle) || normalizedTitle.includes(tTitle);
          }) || items[0];
          if (match?.id) return match.id;
        }
      }
    } catch {}

    return null;
  };

  // Queries Spotify Canvaz Cache endpoint for video Canvas URL
  const fetchSpotifyCanvas = async (trackId, accessToken, clientToken) => {
    if (!trackId || !accessToken) return null;
    try {
      const uri = `spotify:track:${trackId}`;
      const uriBytes = Buffer.from(uri, 'utf8');
      const inner = Buffer.concat([Buffer.from([0x0a, uriBytes.length]), uriBytes]);
      const protobufBody = Buffer.concat([Buffer.from([0x0a, inner.length]), inner]);

      const response = await net.fetch('https://spclient.wg.spotify.com/canvaz-cache/v0/canvases', {
        method: 'POST',
        headers: {
          'Accept': 'application/protobuf',
          'Content-Type': 'application/protobuf',
          'Authorization': `Bearer ${accessToken}`,
          'Client-Token': clientToken || '',
          'User-Agent': userAgent
        },
        body: protobufBody
      });

      if (!response.ok) {
        console.warn('Spotify canvaz request rejected', response.status, await response.text().catch(() => ''));
        return null;
      }

      const buffer = Buffer.from(await response.arrayBuffer());
      const str = buffer.toString('latin1');
      const urlMatch = str.match(/https:\/\/[^"'\s\x00-\x1F]+\.cnvs\.mp4/);
      if (urlMatch) {
        return urlMatch[0];
      }
      // An empty body is a legitimate answer: most tracks simply have no canvas.
      console.log('Spotify canvas: no video for track', trackId, `(${buffer.length} byte response)`);
    } catch (err) {
      console.warn('Fetch Spotify canvas failed', err);
    }
    return null;
  };

  // IPC Handlers
  ipcMain.handle(SPOTIFY.STATUS, async () => {
    return getStatus();
  });

  ipcMain.handle(SPOTIFY.SAVE_SPDC, async (_event, input) => {
    await saveRecord(input?.spdc || '');
    return getStatus();
  });

  ipcMain.handle(SPOTIFY.DISCONNECT, async () => {
    await clearRecord();
    return getStatus();
  });

  ipcMain.handle(SPOTIFY.CONNECT, async () => {
    await loadRecord();
    return new Promise((resolve) => {
      let resolved = false;
      const win = new BrowserWindow({
        width: 600,
        height: 750,
        title: 'Log in to Spotify',
        autoHideMenuBar: true,
        webPreferences: {
          partition: sessionPartition,
          nodeIntegration: false,
          contextIsolation: true
        }
      });
      win.webContents.userAgent = browserUserAgent;

      const finish = async () => {
        if (resolved) return;
        resolved = true;
        try {
          const cookies = await win.webContents.session.cookies.get({ domain: '.spotify.com' });
          const spdc = cookies.find((c) => c.name === 'sp_dc')?.value;
          if (spdc) {
            await saveRecord(spdc);
          }
        } catch {}
        if (!win.isDestroyed()) win.close();
        resolve(await getStatus());
      };

      win.webContents.on('did-navigate', async (_evt, url) => {
        if (/https:\/\/accounts\.spotify\.com\/(?:[^/]+\/)?status/.test(url) || /https:\/\/open\.spotify\.com/.test(url)) {
          await finish();
        }
      });

      const cookieCheckInterval = setInterval(async () => {
        if (win.isDestroyed()) {
          clearInterval(cookieCheckInterval);
          return;
        }
        try {
          const cookies = await win.webContents.session.cookies.get({ domain: '.spotify.com' });
          const spdc = cookies.find((c) => c.name === 'sp_dc')?.value;
          if (spdc) {
            clearInterval(cookieCheckInterval);
            await finish();
          }
        } catch {}
      }, 1000);

      win.on('closed', async () => {
        clearInterval(cookieCheckInterval);
        if (!resolved) {
          resolved = true;
          resolve(await getStatus());
        }
      });

      win.loadURL('https://accounts.spotify.com/en/login', { userAgent: browserUserAgent });
    });
  });

  ipcMain.handle(SPOTIFY.GET_CANVAS, async (_event, input) => {
    await loadRecord();
    if (!spdcToken) return null;

    const target = input?.target;
    const title = cleanText(target?.title);
    const artist = cleanText(target?.artist || target?.artists?.join(', '));
    if (!title) return null;

    const cacheKey = `${title.toLowerCase()}::${artist.toLowerCase()}`;
    if (canvasCache.has(cacheKey)) return canvasCache.get(cacheKey);

    const resolve = async () => {
      const accessToken = await getAccessToken();
      if (!accessToken) return null;

      const clientToken = await getClientToken();
      const trackId = await searchSpotifyTrackId(accessToken, title, artist, clientToken);
      if (!trackId) {
        console.warn('Spotify canvas: no track match for', `${title} - ${artist}`);
        return null;
      }

      const canvasUrl = await fetchSpotifyCanvas(trackId, accessToken, clientToken);
      if (!canvasUrl) return null;

      return {
        name: title,
        artist,
        static: '',
        animated: canvasUrl,
        videoUrl: canvasUrl
      };
    };

    const result = await resolve();
    canvasCache.set(cacheKey, result);
    return result;
  });
}
