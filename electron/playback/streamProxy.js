// Validates upstream media URLs and forwards bounded range responses to the loopback player.
const youtubeWebUserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';
const androidVrUserAgents = new Map([
  ['1.65.10', 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip'],
  ['1.61.48', 'com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)'],
  ['1.43.32', 'com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)']
]);
const playbackProbeRanges = ['bytes=0-0'];

export function rangeNotSatisfiable(res, totalLength) {
  res.writeHead(416, {
    'Accept-Ranges': 'bytes',
    ...(totalLength ? { 'Content-Range': `bytes */${totalLength}` } : {})
  });
  res.end();
}

export function parseRangeHeader(rangeHeader, totalLength = 0) {
  if (!rangeHeader) return { ok: true, wantsRange: false, start: 0 };

  const rangeMatch = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader);
  if (!rangeMatch) return { ok: false };

  const [, startText, endText] = rangeMatch;
  if (!startText && !endText) return { ok: false };

  if (!startText) {
    const suffixLength = Number(endText);
    if (!Number.isFinite(suffixLength) || suffixLength <= 0) return { ok: false };
    const start = totalLength ? Math.max(0, totalLength - suffixLength) : 0;
    return { ok: true, wantsRange: true, start, suffixLength };
  }

  const start = Number(startText);
  const requestedEnd = endText ? Number(endText) : null;
  if (!Number.isFinite(start) || start < 0) return { ok: false };
  if (requestedEnd !== null && (!Number.isFinite(requestedEnd) || requestedEnd < start)) return { ok: false };
  if (totalLength && start >= totalLength) return { ok: false };

  return { ok: true, wantsRange: true, start, requestedEnd };
}

export function upstreamRangeHeader(range, totalLength = 0) {
  if (!range) return '';
  if (!range.wantsRange) return '';

  if (range.suffixLength) return `bytes=-${range.suffixLength}`;
  return `bytes=${range.start}-${range.requestedEnd ?? ''}`;
}

function cleanContentType(contentType, upstreamContentType = '') {
  const baseContentType = contentType.split(';', 1)[0].trim().toLowerCase();
  return baseContentType
    ? baseContentType
    : upstreamContentType && upstreamContentType !== 'application/octet-stream'
    ? upstreamContentType
    : contentType;
}

export function proxyHeadResponseHeaders(contentType, totalLength) {
  const headers = {
    'Content-Type': cleanContentType(contentType),
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Expose-Headers': 'Accept-Ranges, Content-Length, Content-Range'
  };
  if (totalLength) headers['Content-Length'] = String(totalLength);
  return headers;
}

export function proxyResponseHeaders(upstream, contentType, totalLength, wantsRange) {
  const upstreamContentType = upstream.headers.get('content-type');
  const responseContentType = cleanContentType(contentType, upstreamContentType);
  const headers = {
    'Content-Type': responseContentType,
    'Accept-Ranges': upstream.headers.get('accept-ranges') || 'bytes',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Expose-Headers': 'Accept-Ranges, Content-Length, Content-Range'
  };
  const contentLength = upstream.headers.get('content-length');
  const contentRange = upstream.headers.get('content-range');

  if (contentLength) headers['Content-Length'] = contentLength;
  else if (!wantsRange && totalLength) headers['Content-Length'] = String(totalLength);
  if (contentRange && upstream.status === 206) headers['Content-Range'] = contentRange;

  return headers;
}

export async function pipeWebBody(body, res, options = {}) {
  if (!body) {
    if (options.end !== false) res.end();
    return;
  }

  const reader = body.getReader();
  try {
    while (!res.destroyed) {
      const chunk = await reader.read();
      if (chunk.done) break;
      if (chunk.value?.byteLength && !res.write(chunk.value)) {
        await new Promise((resolve) => res.once('drain', resolve));
      }
    }
  } finally {
    reader.releaseLock();
  }

  if (options.end !== false && !res.destroyed) res.end();
}

export function upstreamStreamRequest(url, { fallbackUserAgent, rangeHeader } = {}) {
  const upstreamUrl = new URL(url);
  const profile = streamRequestProfile(upstreamUrl, fallbackUserAgent);
  const headers = {
    Accept: '*/*',
    'User-Agent': profile.userAgent,
    'Accept-Encoding': 'identity'
  };

  if (profile.origin) headers.Origin = profile.origin;
  if (profile.referer) headers.Referer = profile.referer;
  if (rangeHeader) headers.Range = rangeHeader;

  return { headers, url: upstreamUrl };
}

export async function validateUpstreamStreamUrl(url, { fallbackUserAgent } = {}) {
  let sawReadableProbe = false;

  for (const rangeHeader of playbackProbeRanges) {
    const request = upstreamStreamRequest(url, { fallbackUserAgent, rangeHeader });
    const response = await fetch(request.url, { headers: request.headers });

    if (response.status === 416) return sawReadableProbe;
    if (!response.ok && response.status !== 206) return false;

    const contentType = (response.headers.get('content-type') || '').toLowerCase();
    if (
      contentType.startsWith('text/') ||
      contentType.startsWith('application/json') ||
      contentType.startsWith('application/xml')
    ) {
      return false;
    }

    if (!response.body) {
      sawReadableProbe = true;
      continue;
    }

    const reader = response.body.getReader();
    try {
      const chunk = await reader.read();
      if (!chunk.done && chunk.value?.byteLength) {
        sawReadableProbe = true;
        continue;
      }
      return false;
    } finally {
      await reader.cancel().catch(() => {});
    }
  }

  return sawReadableProbe;
}

function streamRequestProfile(url, fallbackUserAgent) {
  const clientName = (url.searchParams.get('c') || '').toUpperCase();
  const clientVersion = url.searchParams.get('cver') || '';
  const fallbackLooksNative = /^(?:com\.google\.android|com\.google\.ios)/i.test(fallbackUserAgent || '');

  if (clientName.startsWith('ANDROID_VR')) {
    return {
      userAgent: androidVrUserAgents.get(clientVersion) || fallbackUserAgent || androidVrUserAgents.get('1.65.10'),
      origin: null,
      referer: null
    };
  }

  if (clientName.startsWith('ANDROID')) {
    return {
      userAgent: fallbackUserAgent || 'com.google.android.youtube/21.10.38 (Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip',
      origin: null,
      referer: null
    };
  }

  if (clientName.startsWith('IOS')) {
    return {
      userAgent: fallbackUserAgent || 'com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)',
      origin: null,
      referer: null
    };
  }

  if (clientName.startsWith('TVHTML5')) {
    return {
      userAgent: fallbackUserAgent || 'Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15',
      origin: 'https://www.youtube.com',
      referer: 'https://www.youtube.com/tv'
    };
  }

  return fallbackLooksNative ? {
    userAgent: fallbackUserAgent,
    origin: null,
    referer: null
  } : {
    userAgent: fallbackUserAgent || youtubeWebUserAgent,
    origin: 'https://music.youtube.com',
    referer: 'https://music.youtube.com/'
  };
}
