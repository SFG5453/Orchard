import { createHash } from 'node:crypto';

const LASTFM_API_ENDPOINT = 'https://ws.audioscrobbler.com/2.0/';
const LASTFM_AUTH_ENDPOINT = 'https://www.last.fm/api/auth/';
const MAX_BODY_BYTES = 32 * 1024;

const responseHeaders = {
  'cache-control': 'no-store',
  'content-type': 'application/json; charset=utf-8',
  'x-content-type-options': 'nosniff'
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === 'GET' && url.pathname === '/health') {
      return jsonResponse({ ok: true, service: 'orchard-lastfm' });
    }
    if (request.method !== 'POST') return jsonResponse({ error: 'Method not allowed.' }, 405);
    if (!env.LASTFM_API_KEY || !env.LASTFM_SHARED_SECRET) {
      return jsonResponse({ error: 'Last.fm scrobbling is not configured yet.' }, 503);
    }

    try {
      if (url.pathname === '/auth/token') return await requestToken(env);

      const input = await readJsonBody(request);
      if (url.pathname === '/auth/session') return await exchangeSession(env, input);
      if (url.pathname === '/now-playing') return await submitTrack(env, input, false);
      if (url.pathname === '/scrobble') return await submitTrack(env, input, true);
      return jsonResponse({ error: 'Not found.' }, 404);
    } catch (error) {
      const status = Number(error?.status) || 500;
      const message = status >= 500 ? 'Last.fm is temporarily unavailable.' : error.message;
      if (status >= 500) {
        console.error(JSON.stringify({
          message: 'lastfm gateway request failed',
          path: url.pathname,
          error: error instanceof Error ? error.message : String(error)
        }));
      }
      return jsonResponse({ error: message }, status);
    }
  }
};

async function requestToken(env) {
  const payload = await callLastfm(env, 'auth.getToken');
  const token = cleanOpaqueToken(payload.token, 'Last.fm returned an invalid authorization token.', 502);
  const authorizationUrl = new URL(LASTFM_AUTH_ENDPOINT);
  authorizationUrl.searchParams.set('api_key', env.LASTFM_API_KEY);
  authorizationUrl.searchParams.set('token', token);
  return jsonResponse({ token, authorizationUrl: authorizationUrl.toString() });
}

async function exchangeSession(env, input) {
  const token = cleanOpaqueToken(input.token, 'The Last.fm authorization token is invalid.');
  const payload = await callLastfm(env, 'auth.getSession', { token });
  const sessionKey = cleanOpaqueToken(payload.session?.key, 'Last.fm did not return a session key.', 502);
  const user = cleanText(payload.session?.name, 100);
  if (!user) throw requestError('Last.fm did not return an account name.', 502);
  return jsonResponse({ user, sessionKey });
}

async function submitTrack(env, input, scrobble) {
  const sessionKey = cleanOpaqueToken(input.sessionKey, 'The Last.fm session is invalid.');
  const track = normalizeTrack(input.track);
  if (!track) throw requestError('Track title and artist are required.', 400);
  if (scrobble && track.duration <= 30) {
    throw requestError('Tracks must be longer than 30 seconds to scrobble.', 400);
  }

  const params = {
    artist: track.artist,
    track: track.title,
    sk: sessionKey
  };
  if (track.album) params.album = track.album;
  if (track.albumArtist) params.albumArtist = track.albumArtist;
  if (track.duration) params.duration = track.duration;
  if (scrobble) params.timestamp = validTimestamp(input.timestamp);

  const method = scrobble ? 'track.scrobble' : 'track.updateNowPlaying';
  const payload = await callLastfm(env, method, params);
  const ignored = scrobble
    ? payload.scrobbles?.scrobble?.ignoredMessage
    : payload.nowplaying?.ignoredMessage;
  return jsonResponse({
    ok: true,
    ignored: String(ignored?.code || '0') !== '0',
    message: cleanText(ignored?.['#text'], 240)
  });
}

export async function callLastfm(env, method, values = {}, fetcher = fetch) {
  const params = {
    ...values,
    api_key: env.LASTFM_API_KEY,
    method
  };
  params.api_sig = lastfmSignature(params, env.LASTFM_SHARED_SECRET);
  params.format = 'json';

  const body = new URLSearchParams(Object.entries(params).map(([key, value]) => [key, String(value)]));
  const write = method.startsWith('track.');
  const url = write ? LASTFM_API_ENDPOINT : `${LASTFM_API_ENDPOINT}?${body}`;
  const response = await fetcher(url, {
    method: write ? 'POST' : 'GET',
    headers: {
      accept: 'application/json',
      ...(write ? { 'content-type': 'application/x-www-form-urlencoded; charset=UTF-8' } : {}),
      'user-agent': 'OrchardDesktop/3.0'
    },
    ...(write ? { body } : {})
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.error) {
    const code = Number(payload.error) || 0;
    throw requestError(
      cleanText(payload.message, 240) || `Last.fm request failed (${response.status}).`,
      lastfmErrorStatus(code, response.status)
    );
  }
  return payload;
}

export function lastfmSignature(params, secret) {
  const source = Object.keys(params)
    .filter((key) => key !== 'format' && key !== 'callback')
    .sort()
    .map((key) => `${key}${params[key] ?? ''}`)
    .join('');
  return createHash('md5').update(`${source}${secret}`, 'utf8').digest('hex');
}

export function normalizeTrack(value) {
  const title = cleanText(value?.title, 500);
  const artist = cleanText(value?.artist, 500);
  if (!title || !artist) return null;
  return {
    title,
    artist,
    album: cleanText(value?.album, 500),
    albumArtist: cleanText(value?.albumArtist, 500),
    duration: Math.max(0, Math.min(86_400, Math.round(Number(value?.duration) || 0)))
  };
}

function validTimestamp(value) {
  const timestamp = Math.floor(Number(value));
  const now = Math.floor(Date.now() / 1000);
  if (!Number.isSafeInteger(timestamp) || timestamp < 1 || timestamp > now + 60) {
    throw requestError('The scrobble timestamp is invalid.', 400);
  }
  return timestamp;
}

async function readJsonBody(request) {
  const contentType = request.headers.get('content-type') || '';
  if (!contentType.toLowerCase().startsWith('application/json')) {
    throw requestError('Content-Type must be application/json.', 415);
  }
  const declaredLength = Number(request.headers.get('content-length'));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) {
    throw requestError('Request body is too large.', 413);
  }
  if (!request.body) return {};

  const reader = request.body.getReader();
  const decoder = new TextDecoder();
  let bytes = 0;
  let text = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    bytes += value.byteLength;
    if (bytes > MAX_BODY_BYTES) {
      await reader.cancel();
      throw requestError('Request body is too large.', 413);
    }
    text += decoder.decode(value, { stream: true });
  }
  text += decoder.decode();
  try {
    const value = JSON.parse(text || '{}');
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('object required');
    return value;
  } catch {
    throw requestError('Request body must be valid JSON.', 400);
  }
}

function cleanText(value, maxLength) {
  return String(value || '').trim().replace(/\s+/g, ' ').slice(0, maxLength);
}

export function cleanOpaqueToken(value, message, status = 400) {
  const text = String(value || '').trim();
  if (text.length < 16 || text.length > 512 || /[^\x21-\x7e]/.test(text)) {
    throw requestError(message, status);
  }
  return text;
}

function lastfmErrorStatus(code, upstreamStatus) {
  if (code === 14) return 409;
  if ([4, 9, 15].includes(code)) return 401;
  if ([6, 10, 13, 26].includes(code)) return 400;
  if (code === 29) return 429;
  return upstreamStatus >= 400 && upstreamStatus < 500 ? upstreamStatus : 502;
}

function requestError(message, status) {
  return Object.assign(new Error(message), { status });
}

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: responseHeaders });
}
