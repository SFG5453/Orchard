const ticketmasterEndpoint = 'https://app.ticketmaster.com/discovery/v2/events.json';
const corsHeaders = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, OPTIONS',
  'access-control-allow-headers': 'Content-Type',
  'x-content-type-options': 'nosniff'
};

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (request.method !== 'GET') return jsonResponse({ error: 'Method not allowed.' }, 405);

    const url = new URL(request.url);
    if (url.pathname === '/health') {
      return jsonResponse({ ok: true, service: 'orchard-concerts' });
    }
    if (url.pathname !== '/events') return jsonResponse({ error: 'Not found.' }, 404);
    if (!env.TICKETMASTER_API_KEY) {
      return jsonResponse({ error: 'Concert discovery is not configured yet.' }, 503);
    }

    const search = searchParameters(url);
    if (!search) {
      return jsonResponse({ error: 'Enter a city, state, or ZIP code, or allow current location.' }, 400);
    }

    const cache = caches.default;
    const cacheKey = new Request(canonicalCacheUrl(url, search), { method: 'GET' });
    const cached = await cache.match(cacheKey);
    if (cached) return cached;

    try {
      const upstreamUrl = ticketmasterUrl(search, env.TICKETMASTER_API_KEY);
      const response = await fetch(upstreamUrl, {
        headers: { accept: 'application/json' }
      });

      if (!response.ok) {
        console.error(JSON.stringify({
          message: 'ticketmaster request failed',
          status: response.status
        }));
        return jsonResponse({ error: 'Ticketmaster is temporarily unavailable.' }, 502);
      }

      const data = await response.json();
      const result = {
        location: search.label,
        events: (data?._embedded?.events || []).map(normalizeEvent).filter(Boolean)
      };
      const normalized = jsonResponse(result, 200, 'public, max-age=300');
      ctx.waitUntil(cache.put(cacheKey, normalized.clone()));
      return normalized;
    } catch (error) {
      console.error(JSON.stringify({
        message: 'concert lookup failed',
        error: error instanceof Error ? error.message : String(error)
      }));
      return jsonResponse({ error: 'Concert discovery is temporarily unavailable.' }, 502);
    }
  }
};

export function searchParameters(url) {
  const latitude = finiteCoordinate(url.searchParams.get('lat'), -90, 90);
  const longitude = finiteCoordinate(url.searchParams.get('lng'), -180, 180);
  if (latitude !== null && longitude !== null) {
    return {
      kind: 'geo',
      geoPoint: encodeGeohash(latitude, longitude, 9),
      label: 'Current location'
    };
  }

  const location = String(url.searchParams.get('location') || '').trim().replace(/\s+/g, ' ');
  if (location.length < 2 || location.length > 100) return null;

  const postalCode = location.match(/^\d{5}(?:-\d{4})?$/)?.[0];
  if (postalCode) return { kind: 'postal', postalCode, label: postalCode };

  const cityState = location.match(/^(.+?),\s*([A-Za-z]{2})$/);
  if (cityState) {
    return {
      kind: 'city',
      city: cityState[1].trim(),
      stateCode: cityState[2].toUpperCase(),
      label: `${cityState[1].trim()}, ${cityState[2].toUpperCase()}`
    };
  }

  return { kind: 'city', city: location, label: location };
}

export function ticketmasterUrl(search, apiKey) {
  const url = new URL(ticketmasterEndpoint);
  url.searchParams.set('apikey', apiKey);
  url.searchParams.set('classificationName', 'Music');
  url.searchParams.set('includeTBA', 'no');
  url.searchParams.set('radius', '75');
  url.searchParams.set('unit', 'miles');
  url.searchParams.set('size', '24');
  url.searchParams.set('sort', 'date,asc');

  if (search.kind === 'geo') url.searchParams.set('geoPoint', search.geoPoint);
  if (search.kind === 'postal') url.searchParams.set('postalCode', search.postalCode);
  if (search.kind === 'city') {
    url.searchParams.set('city', search.city);
    if (search.stateCode) url.searchParams.set('stateCode', search.stateCode);
  }

  return url;
}

export function normalizeEvent(event) {
  const venue = event?._embedded?.venues?.[0] || {};
  const image = [...(event?.images || [])]
    .filter((item) => item?.url)
    .sort((left, right) => (right.width || 0) - (left.width || 0))[0];

  if (!event?.id || !event?.name || !event?.url) return null;

  return {
    id: event.id,
    title: event.name,
    date: event.dates?.start?.localDate || '',
    time: event.dates?.start?.localTime || '',
    timezone: event.dates?.timezone || '',
    venue: venue.name || '',
    city: venue.city?.name || '',
    state: venue.state?.stateCode || venue.state?.name || '',
    country: venue.country?.countryCode || '',
    thumbnail: image?.url || null,
    externalUrl: event.url,
    status: event.dates?.status?.code || ''
  };
}

export function encodeGeohash(latitude, longitude, precision = 9) {
  const alphabet = '0123456789bcdefghjkmnpqrstuvwxyz';
  let latRange = [-90, 90];
  let lngRange = [-180, 180];
  let hash = '';
  let bits = 0;
  let value = 0;
  let useLongitude = true;

  while (hash.length < precision) {
    const range = useLongitude ? lngRange : latRange;
    const coordinate = useLongitude ? longitude : latitude;
    const midpoint = (range[0] + range[1]) / 2;
    value = (value << 1) | (coordinate >= midpoint ? 1 : 0);
    if (coordinate >= midpoint) range[0] = midpoint;
    else range[1] = midpoint;
    useLongitude = !useLongitude;
    bits += 1;

    if (bits === 5) {
      hash += alphabet[value];
      bits = 0;
      value = 0;
    }
  }

  return hash;
}

function finiteCoordinate(value, minimum, maximum) {
  if (value === null || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= minimum && number <= maximum ? number : null;
}

function canonicalCacheUrl(requestUrl, search) {
  const url = new URL('/events', requestUrl.origin);
  if (search.kind === 'geo') url.searchParams.set('geoPoint', search.geoPoint);
  if (search.kind === 'postal') url.searchParams.set('postalCode', search.postalCode);
  if (search.kind === 'city') {
    url.searchParams.set('city', search.city.toLowerCase());
    if (search.stateCode) url.searchParams.set('stateCode', search.stateCode);
  }
  return url;
}

function jsonResponse(data, status = 200, cacheControl = 'no-store') {
  return Response.json(data, {
    status,
    headers: {
      ...corsHeaders,
      'cache-control': cacheControl
    }
  });
}
