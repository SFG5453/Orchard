const responseHeaders = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, HEAD, OPTIONS',
  'cache-control': 'public, max-age=604800, immutable',
  'x-content-type-options': 'nosniff'
};
const conversionVersion = '7';

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: responseHeaders });
    }

    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return textResponse('Method not allowed.', 405);
    }

    const requestUrl = new URL(request.url);
    if (requestUrl.pathname === '/health') {
      return Response.json({ ok: true, service: 'orchard-artwork-proxy' });
    }

    if (!['/', '/convert', '/convert.gif', '/convert.webp'].includes(requestUrl.pathname)) {
      return textResponse('Not found.', 404);
    }

    const sourceUrl = validSourceUrl(requestUrl.searchParams.get('url'));
    if (!sourceUrl) return textResponse('A supported Apple artwork MP4 URL is required.', 400);

    const cache = caches.default;
    const cacheKey = new Request(canonicalCacheUrl(requestUrl, sourceUrl), { method: 'GET' });
    const cached = await cache.match(cacheKey);
    if (cached) return request.method === 'HEAD' ? headResponse(cached) : cached;

    try {
      const upstreamUrl = new URL(env.CONVERTER_URL);
      upstreamUrl.searchParams.set('url', sourceUrl.href);
      const upstream = await fetch(upstreamUrl, {
        headers: {
          accept: 'image/gif',
          authorization: `Bearer ${env.CONVERTER_TOKEN}`
        }
      });

      if (!upstream.ok || !upstream.body) {
        console.error(JSON.stringify({
          message: 'artwork converter request failed',
          status: upstream.status
        }));
        return textResponse('Artwork conversion is temporarily unavailable.', 502);
      }

      const response = new Response(upstream.body, {
        status: 200,
        headers: {
          ...responseHeaders,
          'content-type': 'image/gif',
          'content-length': upstream.headers.get('content-length') || ''
        }
      });
      response.headers.delete('content-length');
      const contentLength = upstream.headers.get('content-length');
      if (contentLength) response.headers.set('content-length', contentLength);

      ctx.waitUntil(cache.put(cacheKey, response.clone()));
      return request.method === 'HEAD' ? headResponse(response) : response;
    } catch (error) {
      console.error(JSON.stringify({
        message: 'artwork proxy request failed',
        error: error instanceof Error ? error.message : String(error)
      }));
      return textResponse('Artwork conversion is temporarily unavailable.', 502);
    }
  }
};

function validSourceUrl(value) {
  if (!value || value.length > 4096) return null;

  try {
    const url = new URL(value);
    if (url.protocol !== 'https:') return null;
    if (url.hostname !== 'mvod.itunes.apple.com') return null;
    if (!url.pathname.toLowerCase().endsWith('.mp4')) return null;
    url.hash = '';
    return url;
  } catch {
    return null;
  }
}

function canonicalCacheUrl(requestUrl, sourceUrl) {
  const url = new URL('/convert.gif', requestUrl.origin);
  url.searchParams.set('v', conversionVersion);
  url.searchParams.set('url', sourceUrl.href);
  return url;
}

function headResponse(response) {
  return new Response(null, { status: response.status, headers: response.headers });
}

function textResponse(message, status) {
  return new Response(message, {
    status,
    headers: {
      ...responseHeaders,
      'cache-control': 'no-store',
      'content-type': 'text/plain; charset=utf-8'
    }
  });
}
