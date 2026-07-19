const ITUNES_SEARCH_ENDPOINT = 'https://itunes.apple.com/search';
const DEFAULT_CACHE_TTL_SECONDS = 90 * 24 * 60 * 60;
const NEGATIVE_CACHE_TTL_SECONDS = 24 * 60 * 60;

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
      return jsonResponse({ ok: true, service: 'orchard-artist-metadata' }, 200, 'public, max-age=60');
    }
    if (url.pathname !== '/artist') return jsonResponse({ error: 'Not found.' }, 404);
    if (!env.DB) return jsonResponse({ error: 'D1 is not configured.' }, 503);

    const input = parseArtistRequest(url);
    if (!input.artist) return jsonResponse({ error: 'artist is required.' }, 400);
    if (!input.album) return jsonResponse({ error: 'album is required for identity confirmation.' }, 400);

    const now = Math.floor(Date.now() / 1000);
    const key = cacheKey(input);
    const cached = await readCachedResult(env.DB, key, now);
    if (cached) return jsonResponse({ ...cached, cached: true }, cached.matched ? 200 : 404, 'public, max-age=86400');

    try {
      const result = await lookupArtistGenre(input, fetch);
      const ttl = result.matched
        ? positiveTtl(env.CACHE_TTL_SECONDS)
        : NEGATIVE_CACHE_TTL_SECONDS;
      const stored = {
        ...result,
        youtubeBrowseId: input.youtubeBrowseId,
        requestedArtist: input.artist,
        requestedAlbum: input.album,
        country: input.country,
        fetchedAt: now,
        expiresAt: now + ttl
      };

      ctx.waitUntil(writeCachedResult(env.DB, key, stored));
      return jsonResponse({ ...stored, cached: false }, result.matched ? 200 : 404, result.matched ? 'public, max-age=86400' : 'public, max-age=3600');
    } catch (error) {
      console.error(JSON.stringify({
        message: 'artist metadata lookup failed',
        artist: input.artist,
        album: input.album,
        error: error instanceof Error ? error.message : String(error)
      }));
      return jsonResponse({ error: 'Artist metadata is temporarily unavailable.' }, 502);
    }
  }
};

export function parseArtistRequest(url) {
  return {
    artist: cleanText(url.searchParams.get('artist'), 160),
    album: cleanText(url.searchParams.get('album'), 220),
    youtubeBrowseId: cleanText(url.searchParams.get('youtubeBrowseId'), 160),
    country: normalizeCountry(url.searchParams.get('country'))
  };
}

export async function lookupArtistGenre(input, fetcher = fetch) {
  const artistPayload = await itunesSearch(fetcher, {
    term: input.artist,
    country: input.country,
    media: 'music',
    entity: 'musicArtist',
    limit: '10'
  });
  const albumPayload = await itunesSearch(fetcher, {
    term: `${input.artist} ${input.album}`,
    country: input.country,
    media: 'music',
    entity: 'album',
    limit: '25'
  });

  const ranked = rankArtistCandidates(
    artistPayload.results || [],
    albumPayload.results || [],
    input.artist,
    input.album
  );
  const winner = ranked[0];

  if (!winner?.confirmedByAlbum || !winner.genre) {
    return {
      ok: true,
      matched: false,
      reason: 'album_confirmation_failed',
      genre: '',
      primaryGenreId: null,
      providerArtistId: '',
      matchedArtistName: '',
      artistLinkUrl: '',
      confirmedAlbum: ''
    };
  }

  return {
    ok: true,
    matched: true,
    reason: '',
    genre: winner.genre,
    primaryGenreId: winner.primaryGenreId,
    providerArtistId: String(winner.artistId),
    matchedArtistName: winner.artistName,
    artistLinkUrl: winner.artistLinkUrl,
    confirmedAlbum: winner.confirmedAlbum,
    confidence: winner.confidence
  };
}

export function rankArtistCandidates(artists, albums, requestedArtist, requestedAlbum) {
  const artistKey = normalizeText(requestedArtist);
  const requestedRelease = releaseKey(requestedAlbum);
  const albumMatches = (albums || []).filter((album) => {
    if (!album?.artistId || !album?.collectionName) return false;
    return normalizeText(album.artistName) === artistKey && releaseMatches(album.collectionName, requestedRelease);
  });

  return (artists || [])
    .filter((artist) => normalizeText(artist.artistName) === artistKey)
    .map((artist) => {
      const confirmation = albumMatches.find((album) => Number(album.artistId) === Number(artist.artistId));
      const genre = cleanText(artist.primaryGenreName || confirmation?.primaryGenreName, 100);
      const confirmedByAlbum = Boolean(confirmation);
      const score = 100 + (genre ? 30 : 0) + (artist.artistLinkUrl ? 5 : 0) + (confirmedByAlbum ? 250 : 0);
      return {
        artistId: artist.artistId,
        artistName: cleanText(artist.artistName, 160),
        genre,
        primaryGenreId: artist.primaryGenreId || confirmation?.primaryGenreId || null,
        artistLinkUrl: cleanText(artist.artistLinkUrl || artist.artistViewUrl, 500),
        confirmedByAlbum,
        confirmedAlbum: cleanText(confirmation?.collectionName, 220),
        confidence: confirmedByAlbum ? 1 : 0.35,
        score
      };
    })
    .sort((left, right) => right.score - left.score || Number(left.artistId) - Number(right.artistId));
}

export function normalizeText(value) {
  return String(value || '')
    .normalize('NFKD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

export function releaseKey(value) {
  return normalizeText(value)
    .replace(/\b(deluxe|expanded|anniversary|remaster(?:ed)?|bonus|edition|version)\b/g, ' ')
    .replace(/\b(single|ep)\b$/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function releaseMatches(candidate, requestedKey) {
  const candidateKey = releaseKey(candidate);
  if (!candidateKey || !requestedKey) return false;
  return candidateKey === requestedKey || candidateKey.startsWith(`${requestedKey} `) || requestedKey.startsWith(`${candidateKey} `);
}

async function itunesSearch(fetcher, params) {
  const url = new URL(ITUNES_SEARCH_ENDPOINT);
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const response = await fetcher(url, {
      headers: {
        accept: 'application/json',
        'user-agent': 'Orchard Artist Metadata/2.1'
      }
    });
    if (response.ok) return response.json();
    if (response.status !== 429 || attempt === 2) {
      throw new Error(`iTunes Search API returned ${response.status}`);
    }

    const retryAfter = Number(response.headers.get('retry-after'));
    const delayMs = Number.isFinite(retryAfter) && retryAfter > 0
      ? Math.min(retryAfter * 1000, 2000)
      : 400 * (attempt + 1);
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }

  throw new Error('iTunes Search API request retry limit reached.');
}

async function readCachedResult(db, key, now) {
  const row = await db.prepare(`
    SELECT * FROM artist_genre_cache
    WHERE cache_key = ? AND expires_at > ?
  `).bind(key, now).first();
  return row ? rowToResult(row) : null;
}

async function writeCachedResult(db, key, result) {
  await db.prepare(`
    INSERT INTO artist_genre_cache (
      cache_key, youtube_browse_id, requested_artist, requested_album, country,
      matched, reason, genre, primary_genre_id, provider_artist_id,
      matched_artist_name, artist_link_url, confirmed_album, confidence,
      fetched_at, expires_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(cache_key) DO UPDATE SET
      youtube_browse_id = excluded.youtube_browse_id,
      requested_artist = excluded.requested_artist,
      requested_album = excluded.requested_album,
      country = excluded.country,
      matched = excluded.matched,
      reason = excluded.reason,
      genre = excluded.genre,
      primary_genre_id = excluded.primary_genre_id,
      provider_artist_id = excluded.provider_artist_id,
      matched_artist_name = excluded.matched_artist_name,
      artist_link_url = excluded.artist_link_url,
      confirmed_album = excluded.confirmed_album,
      confidence = excluded.confidence,
      fetched_at = excluded.fetched_at,
      expires_at = excluded.expires_at
  `).bind(
    key,
    result.youtubeBrowseId,
    result.requestedArtist,
    result.requestedAlbum,
    result.country,
    result.matched ? 1 : 0,
    result.reason || '',
    result.genre || '',
    result.primaryGenreId,
    result.providerArtistId || '',
    result.matchedArtistName || '',
    result.artistLinkUrl || '',
    result.confirmedAlbum || '',
    Number(result.confidence || 0),
    result.fetchedAt,
    result.expiresAt
  ).run();
}

function rowToResult(row) {
  return {
    ok: true,
    matched: Boolean(row.matched),
    reason: row.reason || '',
    genre: row.genre || '',
    primaryGenreId: row.primary_genre_id ?? null,
    providerArtistId: row.provider_artist_id || '',
    matchedArtistName: row.matched_artist_name || '',
    artistLinkUrl: row.artist_link_url || '',
    confirmedAlbum: row.confirmed_album || '',
    confidence: Number(row.confidence || 0),
    youtubeBrowseId: row.youtube_browse_id || '',
    requestedArtist: row.requested_artist || '',
    requestedAlbum: row.requested_album || '',
    country: row.country || 'US',
    fetchedAt: Number(row.fetched_at || 0),
    expiresAt: Number(row.expires_at || 0)
  };
}

function cacheKey(input) {
  return [
    'v1',
    input.country,
    input.youtubeBrowseId || normalizeText(input.artist),
    releaseKey(input.album)
  ].join(':');
}

function cleanText(value, maximum = 200) {
  return String(value || '').trim().replace(/\s+/g, ' ').slice(0, maximum);
}

function normalizeCountry(value) {
  const country = String(value || 'US').trim().toUpperCase();
  return /^[A-Z]{2}$/.test(country) ? country : 'US';
}

function positiveTtl(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 86400 ? Math.floor(parsed) : DEFAULT_CACHE_TTL_SECONDS;
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
