const GETSONG_API_ENDPOINT = 'https://api.getsong.co/search/';
const GETSONG_SITE = 'https://getsongbpm.com/';
const MAX_QUERY_LENGTH = 300;
const MAX_UPSTREAM_BYTES = 256 * 1024;

const corsHeaders = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, OPTIONS',
  'access-control-allow-headers': 'Content-Type',
  'access-control-max-age': '86400',
  'x-content-type-options': 'nosniff'
};

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    const url = new URL(request.url);
    const pathname = normalizedPathname(url.pathname);

    if (request.method !== 'GET') {
      return jsonResponse({ error: 'Method not allowed.' }, 405, { allow: 'GET, OPTIONS' });
    }
    if (pathname === '/') return htmlResponse(landingPage(url.origin));
    if (pathname === '/health') {
      return jsonResponse({ ok: true, service: 'orchard-bpm' });
    }
    if (pathname !== '/bpm') return jsonResponse({ error: 'Not found.' }, 404);
    if (!env.GETSONG_API_KEY) {
      return jsonResponse({ error: 'BPM lookup is not configured yet.' }, 503);
    }

    let query;
    try {
      query = parseLookup(url);
    } catch (error) {
      return jsonResponse({ error: error.message }, Number(error.status) || 400);
    }

    const cache = caches.default;
    const cacheKey = new Request(canonicalCacheUrl(url.origin, query), { method: 'GET' });
    const cached = await cache.match(cacheKey);
    if (cached) return withHeader(cached, 'x-orchard-cache', 'HIT');

    try {
      const song = await lookupSong(env.GETSONG_API_KEY, query);
      if (!song) {
        return jsonResponse({
          error: 'No BPM match was found.',
          source: sourceAttribution()
        }, 404);
      }

      const response = jsonResponse({ ...song, source: sourceAttribution() }, 200, {
        'cache-control': 'public, max-age=3600, s-maxage=604800',
        'x-orchard-cache': 'MISS'
      });
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
      return response;
    } catch (error) {
      const status = Number(error?.status) || 502;
      console.error(JSON.stringify({
        message: 'GetSongBPM lookup failed',
        status,
        error: error instanceof Error ? error.message : String(error)
      }));
      return jsonResponse({
        error: status === 429
          ? 'BPM lookup is busy. Try again shortly.'
          : 'BPM lookup is temporarily unavailable.'
      }, status === 429 ? 429 : 502);
    }
  }
};

export function parseLookup(url) {
  const title = cleanQuery(url.searchParams.get('title'));
  const artist = cleanQuery(url.searchParams.get('artist'));
  if (!title) throw requestError('The title query parameter is required.', 400);
  return { title, artist };
}

export function buildSearchUrl(query) {
  const url = new URL(GETSONG_API_ENDPOINT);
  url.searchParams.set('type', query.artist ? 'both' : 'song');
  url.searchParams.set(
    'lookup',
    query.artist ? `song:${query.title} artist:${query.artist}` : query.title
  );
  url.searchParams.set('limit', query.artist ? '10' : '8');
  return url;
}

export async function lookupSong(apiKey, query, fetcher = fetch) {
  const response = await fetcher(buildSearchUrl(query), {
    headers: {
      accept: 'application/json',
      'x-api-key': apiKey
    }
  });
  const payload = await readLimitedJson(response, MAX_UPSTREAM_BYTES);
  if (!response.ok || payload?.error) {
    throw requestError('GetSongBPM request failed.', response.status === 429 ? 429 : 502);
  }
  return chooseBestMatch(payload?.search, query);
}

export function chooseBestMatch(results, query) {
  if (!Array.isArray(results)) return null;

  const ranked = results
    .map((candidate) => {
      const song = normalizeSong(candidate);
      if (!song) return null;
      const titleScore = textMatchScore(song.title, query.title);
      const artistScore = query.artist ? textMatchScore(song.artist, query.artist) : 1;
      if (titleScore < 0.45 || artistScore < 0.35) return null;
      return {
        song,
        score: query.artist ? (titleScore * 0.7) + (artistScore * 0.3) : titleScore
      };
    })
    .filter(Boolean)
    .sort((left, right) => right.score - left.score);

  return ranked[0]?.song || null;
}

export function normalizeSong(value) {
  const title = cleanText(value?.title, MAX_QUERY_LENGTH);
  const artistValue = Array.isArray(value?.artist) ? value.artist[0] : value?.artist;
  const artist = cleanText(artistValue?.name, MAX_QUERY_LENGTH);
  const bpm = Number(value?.tempo);
  if (!value?.id || !title || !artist || !Number.isFinite(bpm) || bpm <= 0) return null;

  const albumValue = Array.isArray(value?.album) ? value.album[0] : value?.album;
  return {
    id: cleanText(value.id, 100),
    title,
    artist,
    album: cleanText(albumValue?.title, MAX_QUERY_LENGTH) || null,
    bpm: Math.round(bpm * 100) / 100,
    key: cleanText(value?.key_of, 32) || null,
    openKey: cleanText(value?.open_key, 32) || null,
    timeSignature: cleanText(value?.time_sig, 16) || null,
    danceability: percentageOrNull(value?.danceability),
    acousticness: percentageOrNull(value?.acousticness),
    songUrl: safeGetSongUrl(value?.uri)
  };
}

function cleanQuery(value) {
  const text = cleanText(value, MAX_QUERY_LENGTH + 1);
  if (text.length > MAX_QUERY_LENGTH) {
    throw requestError(`Query parameters cannot exceed ${MAX_QUERY_LENGTH} characters.`, 400);
  }
  return text;
}

function cleanText(value, maxLength) {
  return String(value ?? '').trim().replace(/\s+/g, ' ').slice(0, maxLength);
}

function textMatchScore(left, right) {
  const normalizedLeft = comparableText(left);
  const normalizedRight = comparableText(right);
  if (!normalizedLeft || !normalizedRight) return 0;
  if (normalizedLeft === normalizedRight) return 1;
  if (
    normalizedLeft.startsWith(`${normalizedRight} `) ||
    normalizedRight.startsWith(`${normalizedLeft} `)
  ) return 0.9;

  const leftTokens = new Set(normalizedLeft.split(' '));
  const rightTokens = new Set(normalizedRight.split(' '));
  let overlap = 0;
  for (const token of leftTokens) {
    if (rightTokens.has(token)) overlap += 1;
  }
  return overlap / Math.max(leftTokens.size, rightTokens.size);
}

function comparableText(value) {
  return cleanText(value, MAX_QUERY_LENGTH)
    .normalize('NFKD')
    .replace(/\p{Mark}/gu, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function percentageOrNull(value) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  if (!Number.isFinite(number)) return null;
  return Math.max(0, Math.min(100, Math.round(number)));
}

function safeGetSongUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:') return null;
    if (url.hostname !== 'getsongbpm.com' && !url.hostname.endsWith('.getsongbpm.com')) return null;
    return url.toString();
  } catch {
    return null;
  }
}

async function readLimitedJson(response, maximumBytes) {
  if (!response.body) return {};
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let bytes = 0;
  let text = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    bytes += value.byteLength;
    if (bytes > maximumBytes) {
      await reader.cancel();
      throw requestError('GetSongBPM returned too much data.', 502);
    }
    text += decoder.decode(value, { stream: true });
  }
  text += decoder.decode();

  try {
    return JSON.parse(text || '{}');
  } catch {
    throw requestError('GetSongBPM returned invalid JSON.', 502);
  }
}

function canonicalCacheUrl(origin, query) {
  const url = new URL('/bpm', origin);
  url.searchParams.set('title', comparableText(query.title));
  if (query.artist) url.searchParams.set('artist', comparableText(query.artist));
  return url;
}

function normalizedPathname(pathname) {
  return pathname === '/' ? '/' : pathname.replace(/\/+$/, '');
}

function sourceAttribution() {
  return { name: 'GetSongBPM', url: GETSONG_SITE };
}

function withHeader(response, name, value) {
  const headers = new Headers(response.headers);
  headers.set(name, value);
  return new Response(response.body, { status: response.status, headers });
}

function requestError(message, status) {
  return Object.assign(new Error(message), { status });
}

function jsonResponse(data, status = 200, extraHeaders = {}) {
  return Response.json(data, {
    status,
    headers: {
      ...corsHeaders,
      'cache-control': 'no-store',
      link: `<${GETSONG_SITE}>; rel="source"`,
      ...extraHeaders
    }
  });
}

function htmlResponse(html) {
  return new Response(html, {
    headers: {
      'cache-control': 'public, max-age=3600',
      'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
      'content-type': 'text/html; charset=utf-8',
      'referrer-policy': 'no-referrer',
      'x-content-type-options': 'nosniff'
    }
  });
}

function landingPage(origin) {
  const endpoint = `${origin}/bpm?title=Master%20of%20Puppets&artist=Metallica`;
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Orchard BPM</title>
  <style>
    :root { color-scheme: dark; font-family: "IBM Plex Sans", "Helvetica Neue", sans-serif; background: #050605; color: #edf1ee; }
    * { box-sizing: border-box; }
    body { margin: 0; min-height: 100vh; }
    main { width: min(720px, calc(100% - 40px)); margin: 0 auto; padding: 72px 0; }
    .brand { margin: 0 0 12px; color: #67d98b; font-size: 15px; font-weight: 700; }
    .intro { max-width: 580px; margin: 0 0 40px; color: #bdc4bf; font-size: 20px; line-height: 1.5; }
    form { display: grid; grid-template-columns: 1fr 1fr auto; gap: 12px; padding: 24px 0; border-block: 1px solid #242a25; }
    label { display: grid; gap: 8px; color: #aeb5b0; font-size: 13px; }
    input, button { min-height: 44px; border: 1px solid #343b36; border-radius: 8px; font: inherit; }
    input { width: 100%; padding: 0 12px; background: #0b0e0c; color: #f3f5f4; }
    input:focus { outline: 2px solid #67d98b; outline-offset: 2px; }
    button { align-self: end; padding: 0 18px; background: #67d98b; border-color: #67d98b; color: #071009; font-weight: 700; cursor: pointer; }
    button:focus-visible, a:focus-visible { outline: 2px solid #edf1ee; outline-offset: 3px; }
    .docs { margin-top: 32px; color: #929b94; line-height: 1.6; }
    code { color: #d9ddda; font-family: "IBM Plex Mono", "SFMono-Regular", monospace; overflow-wrap: anywhere; }
    a { color: #83eca2; text-underline-offset: 3px; }
    @media (max-width: 640px) { main { padding: 40px 0; } form { grid-template-columns: 1fr; } button { justify-self: start; } }
  </style>
</head>
<body>
  <main>
    <p class="brand">Orchard BPM</p>
    <p class="intro">Look up a song’s tempo and musical key without exposing the upstream API key.</p>
    <form method="get" action="/bpm">
      <label>Song title<input name="title" value="Master of Puppets" maxlength="300" required></label>
      <label>Artist<input name="artist" value="Metallica" maxlength="300"></label>
      <button type="submit">Look up BPM</button>
    </form>
    <div class="docs">
      <p>JSON endpoint: <code>${escapeHtml(endpoint)}</code></p>
      <p>Tempo data provided by <a href="${GETSONG_SITE}">GetSongBPM</a>.</p>
    </div>
  </main>
</body>
</html>`;
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
