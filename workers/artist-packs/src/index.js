const corsHeaders = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, HEAD, OPTIONS',
  'access-control-allow-headers': 'accept, if-none-match, if-modified-since',
  'x-content-type-options': 'nosniff'
};

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return textResponse('Method not allowed.', 405);
    }

    const url = new URL(request.url);
    if (url.pathname === '/health') {
      return Response.json({ ok: true, service: 'orchard-artist-packs' }, { headers: corsHeaders });
    }

    const packPath = cleanPackPath(url.pathname);
    if (!packPath) return textResponse('Not found.', 404);

    const object = await env.PACK_BUCKET.get(objectKey(env, packPath));
    if (!object) return textResponse('Not found.', 404);

    const headers = new Headers();
    for (const [name, value] of Object.entries(corsHeaders)) headers.set(name, value);
    headers.set('cache-control', cacheHeaderFor(packPath));
    headers.set('content-type', contentTypeFor(packPath));
    headers.set('etag', object.httpEtag);
    object.writeHttpMetadata(headers);

    return new Response(request.method === 'HEAD' ? null : object.body, {
      status: 200,
      headers
    });
  }
};

function cleanPackPath(pathname) {
  if (!pathname.startsWith('/v1/')) return null;
  if (pathname.includes('..') || pathname.includes('//')) return null;
  const archivePattern = 'orchard-official-artists(?:-[a-zA-Z0-9._-]+)?\\.orchardpack(?:\\.zst)?';
  if (!new RegExp(`^/v1/(?:index\\.json|${archivePattern})$`).test(pathname)) return null;
  return pathname;
}

function objectKey(env, pathname) {
  const prefix = String(env.PACK_PREFIX || 'artist-packs').replace(/^\/+|\/+$/g, '');
  return `${prefix}/${pathname.replace(/^\/+/, '')}`;
}

function cacheTtlFor(pathname) {
  if (pathname.endsWith('/index.json')) return 300;
  if (pathname.endsWith('.orchardpack') || pathname.endsWith('.orchardpack.zst')) return 86400;
  return 3600;
}

function cacheHeaderFor(pathname) {
  const ttl = cacheTtlFor(pathname);
  const stale = pathname.endsWith('/index.json') ? 3600 : 86400;
  return `public, max-age=${ttl}, stale-while-revalidate=${stale}`;
}

function contentTypeFor(pathname) {
  if (pathname.endsWith('.json')) return 'application/json; charset=utf-8';
  if (pathname.endsWith('.zst')) return 'application/zstd';
  if (pathname.endsWith('.orchardpack')) return 'application/zip';
  return 'application/octet-stream';
}

function textResponse(message, status) {
  return new Response(message, {
    status,
    headers: {
      ...corsHeaders,
      'cache-control': 'no-store',
      'content-type': 'text/plain; charset=utf-8'
    }
  });
}
