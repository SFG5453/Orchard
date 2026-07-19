const jsonHeaders = {
  'content-type': 'application/json; charset=utf-8',
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'content-type, authorization'
};

const textHeaders = {
  'content-type': 'text/plain; charset=utf-8',
  'access-control-allow-origin': '*'
};

const htmlHeaders = {
  'content-type': 'text/html; charset=utf-8',
  'access-control-allow-origin': '*'
};

let tidalTokenCache = null;

const resolverDefinitions = [
  {
    id: 'youtube_music',
    label: 'YouTube Music',
    resolve: async (song) => song.youtube_video_id ? directLink({
      platform: 'youtube_music',
      label: 'YouTube Music',
      url: `https://music.youtube.com/watch?v=${encodeURIComponent(song.youtube_video_id)}`,
      source: 'youtube_video_id'
    }) : null
  },
  {
    id: 'youtube',
    label: 'YouTube',
    resolve: async (song) => song.youtube_video_id ? directLink({
      platform: 'youtube',
      label: 'YouTube',
      url: `https://www.youtube.com/watch?v=${encodeURIComponent(song.youtube_video_id)}`,
      source: 'youtube_video_id'
    }) : null
  },
  {
    id: 'apple_music',
    label: 'Apple Music',
    resolve: resolveAppleMusicLink
  },
  {
    id: 'spotify',
    label: 'Spotify',
    resolve: resolveSpotifySearchLink
  },
  {
    id: 'tidal',
    label: 'TIDAL',
    resolve: resolveTidalLink
  },
  {
    id: 'deezer',
    label: 'Deezer',
    resolve: resolveDeezerLink
  }
];

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: jsonHeaders });

    try {
      const url = new URL(request.url);

      if (url.pathname === '/health') {
        return json({ ok: true, service: 'orchard-song-links' });
      }

      if (url.pathname === '/resolve') {
        if (request.method !== 'GET' && request.method !== 'POST') return methodNotAllowed();
        const payload = request.method === 'POST'
          ? await request.json().catch(() => ({}))
          : Object.fromEntries(url.searchParams.entries());
        const result = await resolveSong(env.DB, env, payload, request);
        return json(result);
      }

      if (url.pathname === '/collections/resolve') {
        if (request.method !== 'GET' && request.method !== 'POST') return methodNotAllowed();
        const payload = request.method === 'POST'
          ? await request.json().catch(() => ({}))
          : Object.fromEntries(url.searchParams.entries());
        const result = await resolveCollection(env.DB, payload);
        return json(result);
      }

      const shareMatch = url.pathname.match(/^\/s\/([^/]+)$/);
      if (shareMatch && request.method === 'GET') {
        const result = await loadResolvedSong(env.DB, shareMatch[1]);
        if (!result) return notFound('Song link not found.');
        return new Response(renderSharePage(result), { headers: htmlHeaders });
      }

      const collectionShareMatch = url.pathname.match(/^\/c\/([^/]+)$/);
      if (collectionShareMatch && request.method === 'GET') {
        const result = await loadResolvedCollection(env.DB, collectionShareMatch[1]);
        if (!result) return notFound('Collection link not found.');
        return new Response(renderCollectionPage(result), { headers: htmlHeaders });
      }

      const apiMatch = url.pathname.match(/^\/api\/songs\/([^/]+)$/);
      if (apiMatch && request.method === 'GET') {
        const result = await loadResolvedSong(env.DB, apiMatch[1]);
        if (!result) return notFound('Song link not found.');
        return json(result);
      }

      const collectionApiMatch = url.pathname.match(/^\/api\/collections\/([^/]+)$/);
      if (collectionApiMatch && request.method === 'GET') {
        const result = await loadResolvedCollection(env.DB, collectionApiMatch[1]);
        if (!result) return notFound('Collection link not found.');
        return json(result);
      }

      return notFound('Unknown Orchard link endpoint.');
    } catch (error) {
      return json({ ok: false, error: error.message || 'Request failed' }, 500);
    }
  }
};

async function resolveSong(db, env, payload, request) {
  const input = normalizeSongPayload(payload);
  if (!input.title || !input.artist) {
    throw new Error('Both title and artist are required.');
  }

  input.thumbnail_url = input.thumbnail_url || await fetchEnhancedArtwork(
    input.title,
    input.artist,
    env.ARTWORK_API_ORIGIN
  );

  const now = new Date().toISOString();
  const existing = await findExistingSong(db, input);
  const id = existing?.id || await stableSongId(input.normalized_key);

  await db.prepare(`
    INSERT INTO songs (
      id, title, artist, album, isrc, youtube_video_id, duration_seconds,
      thumbnail_url, normalized_key, created_at, updated_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      title = excluded.title,
      artist = excluded.artist,
      album = CASE WHEN excluded.album <> '' THEN excluded.album ELSE songs.album END,
      isrc = CASE WHEN excluded.isrc <> '' THEN excluded.isrc ELSE songs.isrc END,
      youtube_video_id = CASE
        WHEN excluded.youtube_video_id <> '' THEN excluded.youtube_video_id
        ELSE songs.youtube_video_id
      END,
      duration_seconds = CASE
        WHEN excluded.duration_seconds > 0 THEN excluded.duration_seconds
        ELSE songs.duration_seconds
      END,
      thumbnail_url = CASE
        WHEN excluded.thumbnail_url <> '' THEN excluded.thumbnail_url
        ELSE songs.thumbnail_url
      END,
      normalized_key = excluded.normalized_key,
      updated_at = excluded.updated_at
  `).bind(
    id,
    input.title,
    input.artist,
    input.album,
    input.isrc,
    input.youtube_video_id,
    input.duration_seconds,
    input.thumbnail_url,
    input.normalized_key,
    now,
    now
  ).run();

  const song = await getSongById(db, id);
  const links = await resolvedLinks(song, env, now);

  await db.prepare(`
    DELETE FROM platform_links
    WHERE song_id = ? AND source = 'generated_search'
  `).bind(song.id).run();

  if (links.length) {
    await db.batch(links.map((link) => db.prepare(`
      INSERT INTO platform_links (
        id, song_id, platform, label, url, confidence, match_type, source, created_at, updated_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(song_id, platform) DO UPDATE SET
        label = excluded.label,
        url = excluded.url,
        confidence = excluded.confidence,
        match_type = excluded.match_type,
        source = excluded.source,
        updated_at = excluded.updated_at
    `).bind(
      `${song.id}:${link.platform}`,
      song.id,
      link.platform,
      link.label,
      link.url,
      link.confidence,
      link.match_type,
      link.source,
      now,
      now
    )));
  }

  await recordResolveEvent(db, song, input.normalized_key, request, now);
  return responseShape(song, await getLinksForSong(db, song.id));
}

function normalizeSongPayload(payload = {}) {
  const title = cleanText(payload.title || payload.name);
  const artist = cleanText(payload.artist || payload.artistName || payload.artists);
  const album = cleanText(payload.album || payload.albumName);
  const isrc = cleanText(payload.isrc).toUpperCase();
  const youtubeVideoId = cleanYoutubeVideoId(payload.youtubeVideoId || payload.youtube_video_id || payload.videoId || payload.id);
  const durationSeconds = positiveInteger(payload.durationSeconds || payload.duration_seconds);
  const thumbnailUrl = cleanUrl(payload.thumbnail || payload.thumbnailUrl || payload.thumbnail_url);
  const normalizedKey = normalizeLookupKey([isrc ? `isrc:${isrc}` : '', artist, title, album].filter(Boolean).join('|'));

  return {
    title,
    artist,
    album,
    isrc,
    youtube_video_id: youtubeVideoId,
    duration_seconds: durationSeconds,
    thumbnail_url: thumbnailUrl,
    normalized_key: normalizedKey || normalizeLookupKey(`${artist}|${title}`)
  };
}

async function findExistingSong(db, input) {
  if (input.isrc) {
    const byIsrc = await db.prepare('SELECT * FROM songs WHERE isrc = ? LIMIT 1').bind(input.isrc).first();
    if (byIsrc) return byIsrc;
  }

  return db.prepare('SELECT * FROM songs WHERE normalized_key = ? LIMIT 1').bind(input.normalized_key).first();
}

async function getSongById(db, id) {
  return db.prepare('SELECT * FROM songs WHERE id = ? LIMIT 1').bind(id).first();
}

async function getLinksForSong(db, songId) {
  return db.prepare(`
    SELECT platform, label, url, confidence, match_type, source
    FROM platform_links
    WHERE song_id = ? AND source <> 'generated_search'
    ORDER BY
      CASE platform
        WHEN 'youtube_music' THEN 1
        WHEN 'youtube' THEN 2
        WHEN 'apple_music' THEN 3
        WHEN 'spotify' THEN 4
        WHEN 'tidal' THEN 5
        ELSE 10
      END,
      label ASC
  `).bind(songId).all().then((result) => result.results || []);
}

async function loadResolvedSong(db, id) {
  const song = await getSongById(db, id);
  if (!song) return null;
  return responseShape(song, await getLinksForSong(db, id));
}

async function resolvedLinks(song, env, now) {
  const settled = await Promise.allSettled(
    resolverDefinitions.map((resolver) => resolver.resolve(song, env))
  );

  return settled
    .map((result, index) => {
      if (result.status === 'fulfilled') return result.value;
      console.warn(`Could not resolve ${resolverDefinitions[index].id}: ${result.reason?.message || result.reason}`);
      return null;
    })
    .filter(Boolean)
    .map((link) => ({
      ...link,
      created_at: now,
      updated_at: now
    }));
}

function directLink({ platform, label, url, source }) {
  return {
    platform,
    label,
    url,
    confidence: 1,
    match_type: 'direct',
    source
  };
}

async function resolveAppleMusicLink(song, env) {
  try {
    const url = new URL('https://itunes.apple.com/search');
    url.searchParams.set('term', linkLookupQuery(song));
    url.searchParams.set('entity', 'song');
    url.searchParams.set('limit', '15');
    url.searchParams.set('country', cleanText(env.DEFAULT_COUNTRY) || 'US');

    const data = await fetchJson(url);
    const candidates = Array.isArray(data.results) ? data.results : [];
    const match = bestMatch(song, candidates, {
      title: (item) => item.trackName,
      artist: (item) => item.artistName,
      album: (item) => item.collectionName,
      url: (item) => item.trackViewUrl
    });

    if (match) {
      return {
        platform: 'apple_music',
        label: 'Apple Music',
        url: match.url,
        confidence: match.score,
        match_type: 'api',
        source: 'itunes_search'
      };
    }
  } catch (error) {
    console.warn(`Could not resolve Apple Music direct link: ${error.message}`);
  }

  return appleMusicSearchLink(song, env);
}

function appleMusicSearchLink(song, env) {
  const country = cleanText(env.DEFAULT_COUNTRY || 'us').toLowerCase();
  const url = new URL(`https://music.apple.com/${country}/search`);
  url.searchParams.set('term', linkLookupQuery(song));

  return {
    platform: 'apple_music',
    label: 'Apple Music',
    url: url.toString(),
    confidence: 0,
    match_type: 'search',
    source: 'apple_music_search'
  };
}

async function resolveDeezerLink(song) {
  const url = new URL('https://api.deezer.com/search/track');
  url.searchParams.set('q', `artist:"${song.artist}" track:"${song.title}"`);
  url.searchParams.set('limit', '15');

  const data = await fetchJson(url);
  const candidates = Array.isArray(data.data) ? data.data : [];
  const match = bestMatch(song, candidates, {
    title: (item) => item.title,
    artist: (item) => item.artist?.name,
    album: (item) => item.album?.title,
    url: (item) => item.link
  });

  if (!match) return null;

  return {
    platform: 'deezer',
    label: 'Deezer',
    url: match.url,
    confidence: match.score,
    match_type: 'api',
    source: 'deezer_search'
  };
}

async function resolveSpotifySearchLink(song) {
  return {
    platform: 'spotify',
    label: 'Spotify',
    url: `https://open.spotify.com/search/${encodeURIComponent(linkLookupQuery(song))}`,
    confidence: 0,
    match_type: 'search',
    source: 'spotify_search'
  };
}

async function resolveTidalLink(song, env) {
  if (!env.TIDAL_CLIENT_ID || !env.TIDAL_CLIENT_SECRET) return tidalSearchLink(song);

  try {
    const token = await tidalAccessToken(env);
    const url = new URL(`https://openapi.tidal.com/v2/searchResults/${encodeURIComponent(linkLookupQuery(song))}`);
    url.searchParams.set('countryCode', cleanText(env.TIDAL_COUNTRY) || 'US');
    url.searchParams.set('include', 'tracks,tracks.artists,tracks.albums');

    const data = await fetchJson(url, {
      headers: {
        accept: 'application/vnd.api+json',
        authorization: `Bearer ${token}`,
        'content-type': 'application/vnd.api+json'
      }
    });
    const match = bestTidalMatch(song, data);
    if (match) return match;
  } catch (error) {
    console.warn(`Could not resolve TIDAL direct link: ${error.message}`);
  }

  return tidalSearchLink(song);
}

function tidalSearchLink(song) {
  const url = new URL('https://listen.tidal.com/search');
  url.searchParams.set('q', linkLookupQuery(song));

  return {
    platform: 'tidal',
    label: 'TIDAL',
    url: url.toString(),
    confidence: 0,
    match_type: 'search',
    source: 'tidal_search'
  };
}

async function tidalAccessToken(env) {
  if (tidalTokenCache && tidalTokenCache.expiresAt > Date.now() + 60_000) {
    return tidalTokenCache.token;
  }

  const response = await fetch('https://auth.tidal.com/v1/oauth2/token', {
    method: 'POST',
    headers: {
      authorization: `Basic ${btoa(`${env.TIDAL_CLIENT_ID}:${env.TIDAL_CLIENT_SECRET}`)}`,
      'content-type': 'application/x-www-form-urlencoded'
    },
    body: 'grant_type=client_credentials'
  });

  if (!response.ok) throw new Error(`TIDAL token request failed with ${response.status}`);
  const data = await response.json();
  if (!data.access_token) throw new Error('TIDAL token response did not include access_token');

  tidalTokenCache = {
    token: data.access_token,
    expiresAt: Date.now() + (positiveInteger(data.expires_in) || 3600) * 1000
  };

  return tidalTokenCache.token;
}

function bestTidalMatch(song, data) {
  const included = Array.isArray(data?.included) ? data.included : [];
  const byTypeAndId = new Map(included.map((item) => [`${item.type}:${item.id}`, item]));
  const tracks = included.filter((item) => item.type === 'tracks');
  const match = bestMatch(song, tracks, {
    title: (item) => item.attributes?.title,
    artist: (item) => tidalRelationshipNames(item, 'artists', byTypeAndId),
    album: (item) => tidalRelationshipNames(item, 'albums', byTypeAndId),
    url: (item) => item.id ? `https://tidal.com/browse/track/${encodeURIComponent(item.id)}` : ''
  });

  if (!match) return null;

  return {
    platform: 'tidal',
    label: 'TIDAL',
    url: match.url,
    confidence: match.score,
    match_type: 'api',
    source: 'tidal_search_api'
  };
}

function tidalRelationshipNames(item, relationshipName, byTypeAndId) {
  const relationship = item.relationships?.[relationshipName]?.data;
  const refs = Array.isArray(relationship) ? relationship : relationship ? [relationship] : [];

  return refs
    .map((ref) => byTypeAndId.get(`${ref.type}:${ref.id}`)?.attributes?.name || byTypeAndId.get(`${ref.type}:${ref.id}`)?.attributes?.title || '')
    .filter(Boolean)
    .join(', ');
}

function linkLookupQuery(song) {
  return [song.artist, song.title, song.album].filter(Boolean).join(' ');
}

async function fetchEnhancedArtwork(title, artist, artworkApiOrigin) {
  if (!title || !artist || !artworkApiOrigin) return '';

  try {
    const url = new URL('/', artworkApiOrigin);
    url.searchParams.set('s', title);
    url.searchParams.set('a', artist);

    const data = await fetchJson(url);
    const result = Array.isArray(data) ? data[0] : data;
    return cleanUrl(result?.static) || '';
  } catch (error) {
    console.warn(`Could not fetch artwork for ${artist} - ${title}: ${error.message}`);
    return '';
  }
}

async function fetchJson(url, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 7000);

  try {
    const response = await fetch(url.toString(), {
      ...options,
      signal: controller.signal,
      headers: {
        accept: 'application/json',
        ...(options.headers || {})
      }
    });

    if (!response.ok) throw new Error(`${url.hostname} returned ${response.status}`);
    return response.json();
  } finally {
    clearTimeout(timeout);
  }
}

function bestMatch(song, candidates, accessors) {
  return candidates
    .map((candidate) => {
      const url = cleanUrl(accessors.url(candidate));
      if (!url) return null;

      return {
        url,
        score: matchScore(song, {
          title: accessors.title(candidate),
          artist: accessors.artist(candidate),
          album: accessors.album(candidate)
        })
      };
    })
    .filter(Boolean)
    .filter((candidate) => candidate.score >= 0.74)
    .sort((left, right) => right.score - left.score)[0] || null;
}

function matchScore(song, candidate) {
  const titleScore = textSimilarity(song.title, candidate.title);
  const artistScore = textSimilarity(song.artist, candidate.artist);
  const albumScore = song.album && candidate.album ? textSimilarity(song.album, candidate.album) : 0;
  const baseScore = (titleScore * 0.56) + (artistScore * 0.36) + (albumScore * 0.08);

  return Math.max(0, Math.min(1, Number(baseScore.toFixed(3))));
}

function textSimilarity(left, right) {
  const leftTokens = new Set(matchTokens(left));
  const rightTokens = new Set(matchTokens(right));
  if (!leftTokens.size || !rightTokens.size) return 0;

  let overlap = 0;
  for (const token of leftTokens) {
    if (rightTokens.has(token)) overlap += 1;
  }

  return overlap / Math.max(leftTokens.size, rightTokens.size);
}

function matchTokens(value) {
  return normalizeLookupKey(value)
    .replace(/[|:]/g, ' ')
    .split(' ')
    .filter((token) => token && !['a', 'an', 'and', 'feat', 'ft', 'the'].includes(token));
}

async function recordResolveEvent(db, song, lookupKey, request, now) {
  const userAgent = request.headers.get('user-agent') || '';

  await db.prepare(`
    INSERT INTO resolve_events (id, song_id, lookup_key, user_agent, created_at)
    VALUES (?, ?, ?, ?, ?)
  `).bind(crypto.randomUUID(), song.id, lookupKey, userAgent.slice(0, 240), now).run();
}

function responseShape(song, links) {
  return {
    ok: true,
    id: song.id,
    shareUrl: `/s/${song.id}`,
    song: {
      title: song.title,
      artist: song.artist,
      album: song.album,
      isrc: song.isrc,
      youtubeVideoId: song.youtube_video_id,
      durationSeconds: song.duration_seconds,
      thumbnailUrl: song.thumbnail_url
    },
    links
  };
}

async function resolveCollection(db, payload) {
  const input = normalizeCollectionPayload(payload);
  if (!input.kind || !input.title) throw new Error('Collection kind and title are required.');

  const now = new Date().toISOString();
  const existing = await db.prepare('SELECT * FROM collections WHERE normalized_key = ? LIMIT 1')
    .bind(input.normalized_key)
    .first();
  const id = existing?.id || await stableSongId(input.normalized_key);

  await db.prepare(`
    INSERT INTO collections (
      id, kind, title, subtitle, browse_id, thumbnail_url, item_count, orchard_only,
      normalized_key, created_at, updated_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      kind = excluded.kind,
      title = excluded.title,
      subtitle = excluded.subtitle,
      browse_id = CASE WHEN excluded.browse_id <> '' THEN excluded.browse_id ELSE collections.browse_id END,
      thumbnail_url = CASE WHEN excluded.thumbnail_url <> '' THEN excluded.thumbnail_url ELSE collections.thumbnail_url END,
      item_count = CASE WHEN excluded.item_count <> '' THEN excluded.item_count ELSE collections.item_count END,
      orchard_only = excluded.orchard_only,
      normalized_key = excluded.normalized_key,
      updated_at = excluded.updated_at
  `).bind(
    id,
    input.kind,
    input.title,
    input.subtitle,
    input.browse_id,
    input.thumbnail_url,
    input.item_count,
    input.orchard_only ? 1 : 0,
    input.normalized_key,
    now,
    now
  ).run();

  await db.prepare('DELETE FROM collection_tracks WHERE collection_id = ?').bind(id).run();

  if (input.tracks.length) {
    await db.batch(input.tracks.map((track, index) => db.prepare(`
      INSERT INTO collection_tracks (
        id, collection_id, position, title, artist, album, youtube_video_id,
        duration_seconds, thumbnail_url, created_at, updated_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      `${id}:${String(index + 1).padStart(4, '0')}`,
      id,
      index + 1,
      track.title,
      track.artist,
      track.album,
      track.youtube_video_id,
      track.duration_seconds,
      track.thumbnail_url,
      now,
      now
    )));
  }

  return loadResolvedCollection(db, id);
}

function normalizeCollectionPayload(payload = {}) {
  const kind = cleanCollectionKind(payload.kind || payload.type);
  const title = cleanText(payload.title || payload.name);
  const subtitle = cleanText(payload.subtitle || payload.artist || payload.creator || payload.description);
  const browseId = cleanText(payload.browseId || payload.browse_id || payload.id);
  const thumbnailUrl = cleanUrl(payload.thumbnail || payload.thumbnailUrl || payload.thumbnail_url);
  const itemCount = cleanText(payload.itemCount || payload.item_count || payload.totalDuration || payload.year);
  const orchardOnly = Boolean(payload.orchardOnly || payload.orchard_only || payload.private);
  const tracks = normalizeCollectionTracks(payload.tracks);
  const normalizedKey = normalizeLookupKey([
    'collection',
    kind,
    browseId || title,
    orchardOnly ? 'orchard' : 'public'
  ].filter(Boolean).join('|'));

  return {
    kind,
    title,
    subtitle,
    browse_id: browseId,
    thumbnail_url: thumbnailUrl,
    item_count: itemCount,
    orchard_only: orchardOnly,
    tracks,
    normalized_key: normalizedKey
  };
}

function normalizeCollectionTracks(tracks) {
  if (!Array.isArray(tracks)) return [];

  return tracks
    .map((track) => ({
      title: cleanText(track?.title || track?.name),
      artist: cleanText(track?.artist || track?.artists),
      album: cleanText(track?.album),
      youtube_video_id: cleanYoutubeVideoId(track?.youtubeVideoId || track?.youtube_video_id || track?.videoId || track?.id),
      duration_seconds: positiveInteger(track?.durationSeconds || track?.duration_seconds),
      thumbnail_url: cleanUrl(track?.thumbnailUrl || track?.thumbnail_url || track?.thumbnail)
    }))
    .filter((track) => track.title && (track.artist || track.youtube_video_id))
    .slice(0, 300);
}

function cleanCollectionKind(value = '') {
  const kind = cleanText(value).toLowerCase();
  return ['artist', 'album', 'playlist', 'podcast'].includes(kind) ? kind : '';
}

async function loadResolvedCollection(db, id) {
  const collection = await db.prepare('SELECT * FROM collections WHERE id = ? LIMIT 1').bind(id).first();
  if (!collection) return null;

  const tracks = await db.prepare(`
    SELECT position, title, artist, album, youtube_video_id, duration_seconds, thumbnail_url
    FROM collection_tracks
    WHERE collection_id = ?
    ORDER BY position ASC
  `).bind(id).all().then((result) => result.results || []);

  return collectionResponseShape(collection, tracks);
}

function collectionResponseShape(collection, tracks = []) {
  const shapedCollection = {
    id: collection.id,
    kind: collection.kind,
    title: collection.title,
    subtitle: collection.subtitle,
    browseId: collection.browse_id,
    thumbnailUrl: collection.thumbnail_url,
    itemCount: collection.item_count,
    orchardOnly: Boolean(collection.orchard_only)
  };

  return {
    ok: true,
    id: collection.id,
    shareUrl: `/c/${collection.id}`,
    collection: shapedCollection,
    tracks: tracks.map((track) => ({
      index: track.position,
      title: track.title,
      artist: track.artist,
      album: track.album,
      youtubeVideoId: track.youtube_video_id,
      durationSeconds: track.duration_seconds,
      duration: track.duration_seconds ? formatDuration(track.duration_seconds) : '',
      thumbnailUrl: track.thumbnail_url
    })),
    links: collectionLinks(shapedCollection)
  };
}

function collectionLinks(collection) {
  const links = [{
    platform: 'orchard',
    label: 'Orchard',
    url: `orchard:share/${encodeURIComponent(collection.id)}`,
    confidence: 1,
    match_type: 'direct',
    source: 'orchard_collection'
  }];

  const youtubeUrl = collection.orchardOnly ? '' : youtubeMusicCollectionUrl(collection);
  if (youtubeUrl) {
    links.push({
      platform: 'youtube_music',
      label: 'YouTube Music',
      url: youtubeUrl,
      confidence: 1,
      match_type: 'direct',
      source: 'youtube_browse_id'
    });
  }

  return links;
}

function youtubeMusicCollectionUrl(collection) {
  const browseId = cleanText(collection.browseId);
  if (!browseId) return '';

  if (collection.kind === 'playlist') {
    const playlistId = browseId.startsWith('VL') ? browseId.slice(2) : browseId;
    return `https://music.youtube.com/playlist?list=${encodeURIComponent(playlistId)}`;
  }

  return `https://music.youtube.com/browse/${encodeURIComponent(browseId)}`;
}

function formatDuration(seconds) {
  const total = positiveInteger(seconds);
  if (!total) return '';
  const mins = Math.floor(total / 60);
  const secs = String(total % 60).padStart(2, '0');
  return `${mins}:${secs}`;
}

async function stableSongId(key) {
  const data = new TextEncoder().encode(key);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return base64Url(digest).slice(0, 22);
}

function base64Url(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function normalizeLookupKey(value) {
  return cleanText(value)
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9:|]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function cleanText(value) {
  if (Array.isArray(value)) return value.map(cleanText).filter(Boolean).join(', ');
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function cleanYoutubeVideoId(value) {
  const text = cleanText(value);
  if (/^[a-zA-Z0-9_-]{11}$/.test(text)) return text;

  try {
    const url = new URL(text);
    const videoId = url.searchParams.get('v') || url.pathname.match(/\/(?:shorts|embed)\/([^/]+)/)?.[1] || '';
    return /^[a-zA-Z0-9_-]{11}$/.test(videoId) ? videoId : '';
  } catch {
    return '';
  }
}

function positiveInteger(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.round(number) : 0;
}

function cleanUrl(value) {
  const text = cleanText(value);
  if (!text) return '';

  try {
    const url = new URL(text);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : '';
  } catch {
    return '';
  }
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), { status, headers: jsonHeaders });
}

function methodNotAllowed() {
  return json({ ok: false, error: 'Method not allowed' }, 405);
}

function notFound(message) {
  return new Response(message, { status: 404, headers: textHeaders });
}

const platformThemes = {
  orchard: {
    color: '#7df49a',
    logoUrl: ''
  },
  youtube_music: {
    color: '#ff2d2d',
    logoUrl: 'https://cdn.simpleicons.org/youtubemusic/FF0000'
  },
  youtube: {
    color: '#ff2d2d',
    logoUrl: 'https://cdn.simpleicons.org/youtube/FF0000'
  },
  apple_music: {
    color: '#fa4f86',
    logoUrl: 'https://cdn.simpleicons.org/applemusic/FA243C'
  },
  spotify: {
    color: '#1ed760',
    logoUrl: 'https://cdn.simpleicons.org/spotify/1ED760'
  },
  tidal: {
    color: '#ffffff',
    logoUrl: 'https://cdn.simpleicons.org/tidal/000000'
  },
  deezer: {
    color: '#a238ff',
    logoUrl: 'https://cdn.simpleicons.org/deezer/A238FF'
  }
};

const orchardLogoDataUri = 'data:image/png;base64,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';

function renderSharePage(result) {
  const title = `${result.song.title} - ${result.song.artist}`;
  const subtitle = [result.song.artist, result.song.album].filter(Boolean).join(' - ');
  const artwork = result.song.thumbnailUrl;
  const links = result.links.length
    ? result.links.map((link) => {
      const theme = platformThemes[link.platform] || {
        color: '#8df0a8',
        logoUrl: ''
      };
      const linkKind = link.match_type === 'search'
        ? `Search on ${link.label}`
        : link.match_type === 'direct' ? 'Direct page' : 'Matched page';
      const actionText = link.match_type === 'search' ? 'Search' : 'Open';

      return `
        <a class="service-card" href="${escapeHtml(link.url)}" target="_blank" rel="noopener noreferrer" style="--service-color: ${theme.color}">
          <span class="service-mark" aria-hidden="true">
            ${theme.logoUrl ? `<img src="${escapeHtml(theme.logoUrl)}" alt="">` : `<span>${escapeHtml(link.label.slice(0, 2).toUpperCase())}</span>`}
          </span>
          <span class="service-copy">
            <strong>${escapeHtml(link.label)}</strong>
            <span>${escapeHtml(linkKind)}</span>
          </span>
          <span class="service-open">${escapeHtml(actionText)}</span>
        </a>
      `;
    }).join('')
    : '<p class="empty-links">No direct links have been found yet.</p>';

  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="icon" type="image/png" href="${orchardLogoDataUri}">
    <title>${escapeHtml(title)}</title>
    <style>
      :root {
        color-scheme: dark;
        font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif;
        background: #101411;
        color: #f4f8f1;
      }
      * {
        box-sizing: border-box;
      }
      body {
        margin: 0;
        min-height: 100vh;
        background:
          linear-gradient(145deg, rgba(20, 184, 166, 0.18), transparent 32%),
          linear-gradient(215deg, rgba(250, 79, 134, 0.16), transparent 36%),
          #101411;
      }
      .page-backdrop {
        position: fixed;
        inset: 0;
        pointer-events: none;
        overflow: hidden;
      }
      .page-backdrop img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        filter: blur(42px) saturate(1.4);
        opacity: 0.16;
        transform: scale(1.12);
      }
      main {
        position: relative;
        width: min(440px, calc(100vw - 28px));
        margin: 0 auto;
        padding: 28px 0 40px;
      }
      .brand {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 8px;
        margin: 0 0 18px;
        color: #b6c4bb;
        font-size: 14px;
        font-weight: 700;
      }
      .brand-mark {
        width: 24px;
        height: 24px;
        display: block;
        object-fit: contain;
      }
      .track-card,
      .links-card {
        border: 1px solid rgba(255, 255, 255, 0.11);
        border-radius: 12px;
        background: rgba(17, 22, 18, 0.86);
        overflow: hidden;
      }
      .art-stage {
        min-height: 286px;
        display: grid;
        place-items: center;
        padding: 28px;
        background:
          linear-gradient(145deg, rgba(125, 244, 154, 0.22), transparent 48%),
          linear-gradient(320deg, rgba(250, 79, 134, 0.22), transparent 54%),
          #151b17;
      }
      .cover {
        width: min(242px, 72vw);
        aspect-ratio: 1;
        object-fit: cover;
        border-radius: 10px;
        border: 1px solid rgba(255, 255, 255, 0.16);
      }
      .cover-fallback {
        width: min(242px, 72vw);
        aspect-ratio: 1;
        display: grid;
        place-items: center;
        border-radius: 10px;
        border: 1px solid rgba(255, 255, 255, 0.14);
        background: linear-gradient(150deg, #70f49a, #ff4f87 54%, #8d6cff);
        color: #111411;
        font-size: 72px;
        font-weight: 900;
      }
      h1 {
        margin: 0;
        font-size: clamp(24px, 8vw, 36px);
        line-height: 1.05;
        letter-spacing: 0;
      }
      .track-copy {
        padding: 22px 24px 20px;
        text-align: center;
      }
      .track-copy p {
        margin: 8px 0 0;
        color: #bac7bf;
        font-size: 15px;
        line-height: 1.45;
      }
      .actions {
        display: grid;
        grid-template-columns: 1fr 1fr;
        border-top: 1px solid rgba(255, 255, 255, 0.09);
      }
      .actions:has(button[hidden]) {
        grid-template-columns: 1fr;
      }
      button {
        min-height: 50px;
        border: 0;
        border-right: 1px solid rgba(255, 255, 255, 0.09);
        background: rgba(255, 255, 255, 0.03);
        color: #9fe7b0;
        font: inherit;
        font-size: 14px;
        font-weight: 800;
        cursor: pointer;
      }
      button:last-child {
        border-right: 0;
      }
      button:hover,
      button:focus-visible {
        background: rgba(125, 244, 154, 0.11);
        outline: none;
      }
      .links-card {
        margin-top: 18px;
        padding: 18px;
      }
      .links-title {
        margin: 0 0 12px;
        color: #f4f8f1;
        font-size: 16px;
        font-weight: 900;
      }
      .services {
        display: grid;
        gap: 9px;
      }
      .service-card {
        display: flex;
        align-items: center;
        gap: 12px;
        min-height: 62px;
        padding: 11px 12px;
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 10px;
        background: rgba(255, 255, 255, 0.045);
        color: #f4f8f1;
        text-decoration: none;
      }
      .service-card:hover,
      .service-card:focus-visible {
        border-color: color-mix(in srgb, var(--service-color), white 18%);
        background: color-mix(in srgb, var(--service-color), transparent 88%);
        outline: none;
      }
      .service-mark {
        width: 40px;
        height: 40px;
        flex: 0 0 auto;
        display: grid;
        place-items: center;
        border-radius: 10px;
        background: rgba(255, 255, 255, 0.96);
        color: #071009;
      }
      .service-mark img {
        width: 24px;
        height: 24px;
        display: block;
      }
      .service-mark span {
        font-size: 12px;
        font-weight: 950;
      }
      .service-copy {
        min-width: 0;
        display: grid;
        gap: 2px;
      }
      .service-copy strong,
      .service-copy span {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .service-copy strong {
        font-size: 15px;
      }
      .service-copy span {
        color: #aeb9b2;
        font-size: 13px;
      }
      .service-open {
        margin-left: auto;
        color: #bdf4ca;
        font-size: 13px;
        font-weight: 900;
      }
      .empty-links {
        margin: 0;
        color: #aeb9b2;
        font-size: 13px;
      }
      .footer-note {
        margin: 18px 0 0;
        text-align: center;
        color: #7f8d84;
        font-size: 13px;
      }
      @media (max-width: 420px) {
        main {
          width: min(100vw - 18px, 440px);
          padding-top: 14px;
        }
        .art-stage {
          min-height: 244px;
          padding: 20px;
        }
        .track-copy {
          padding: 18px 18px 17px;
        }
        .links-card {
          padding: 14px;
        }
      }
    </style>
  </head>
  <body>
    ${artwork ? `<div class="page-backdrop"><img src="${escapeHtml(artwork)}" alt=""></div>` : ''}
    <main>
      <div class="brand"><img class="brand-mark" src="${orchardLogoDataUri}" alt=""><span>Orchard links</span></div>
      <section class="track-card" aria-labelledby="track-title">
        <div class="art-stage">
          ${artwork ? `<img class="cover" src="${escapeHtml(artwork)}" alt="">` : `<div class="cover-fallback" aria-hidden="true">${escapeHtml(result.song.title.slice(0, 1) || 'O')}</div>`}
        </div>
        <div class="track-copy">
          <h1 id="track-title">${escapeHtml(result.song.title)}</h1>
          <p>${escapeHtml(subtitle)}</p>
        </div>
        <div class="actions">
          <button type="button" data-copy>Copy link</button>
          <button type="button" data-share>Share</button>
        </div>
      </section>
      <section class="links-card" aria-labelledby="listen-title">
        <h2 class="links-title" id="listen-title">Listen</h2>
        <div class="services">${links}</div>
      </section>
      <p class="footer-note">Shared with Orchard</p>
    </main>
    <script>
      const copyButton = document.querySelector('[data-copy]');
      const shareButton = document.querySelector('[data-share]');
      const pageTitle = ${scriptJson(title)};

      copyButton?.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(window.location.href);
          copyButton.textContent = 'Copied';
          setTimeout(() => { copyButton.textContent = 'Copy link'; }, 1400);
        } catch {
          copyButton.textContent = 'Copy failed';
          setTimeout(() => { copyButton.textContent = 'Copy link'; }, 1400);
        }
      });

      if (!navigator.share) {
        shareButton.hidden = true;
      } else {
        shareButton.addEventListener('click', async () => {
          await navigator.share({ title: pageTitle, url: window.location.href }).catch(() => {});
        });
      }
    </script>
  </body>
</html>`;
}

function renderCollectionPage(result) {
  const collection = result.collection;
  const title = `${collection.title} - Orchard`;
  const subtitle = [
    collection.subtitle,
    collection.itemCount,
    collection.orchardOnly ? 'Orchard-only playlist snapshot' : ''
  ].filter(Boolean).join(' - ');
  const artwork = collection.thumbnailUrl;
  const links = result.links.map((link) => {
    const theme = platformThemes[link.platform] || {
      color: '#8df0a8',
      logoUrl: ''
    };
    const actionText = link.platform === 'orchard' ? 'Open' : 'Open';

    return `
      <a class="service-card" href="${escapeHtml(link.url)}" target="_blank" rel="noopener noreferrer" style="--service-color: ${theme.color}">
        <span class="service-mark" aria-hidden="true">
          ${theme.logoUrl ? `<img src="${escapeHtml(theme.logoUrl)}" alt="">` : `<span>${escapeHtml(link.label.slice(0, 2).toUpperCase())}</span>`}
        </span>
        <span class="service-copy">
          <strong>${escapeHtml(link.label)}</strong>
          <span>${escapeHtml(link.platform === 'orchard' ? 'Open this link in Orchard' : 'Source collection')}</span>
        </span>
        <span class="service-open">${escapeHtml(actionText)}</span>
      </a>
    `;
  }).join('');
  const tracks = result.tracks.length
    ? result.tracks.map((track) => `
      <li>
        <span class="track-index">${escapeHtml(track.index)}</span>
        <span class="track-title">${escapeHtml(track.title)}</span>
        <span class="track-artist">${escapeHtml(track.artist || track.album || '')}</span>
        <span class="track-time">${escapeHtml(track.duration || '')}</span>
      </li>
    `).join('')
    : '';

  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="icon" type="image/png" href="${orchardLogoDataUri}">
    <title>${escapeHtml(title)}</title>
    <style>
      :root {
        color-scheme: dark;
        font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif;
        background: #101411;
        color: #f4f8f1;
      }
      * {
        box-sizing: border-box;
      }
      body {
        margin: 0;
        min-height: 100vh;
        background: #101411;
      }
      .page-backdrop {
        position: fixed;
        inset: 0;
        pointer-events: none;
        overflow: hidden;
      }
      .page-backdrop img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        filter: blur(42px) saturate(1.25);
        opacity: 0.15;
        transform: scale(1.12);
      }
      main {
        position: relative;
        width: min(520px, calc(100vw - 28px));
        margin: 0 auto;
        padding: 28px 0 40px;
      }
      .brand {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 8px;
        margin: 0 0 18px;
        color: #b6c4bb;
        font-size: 14px;
        font-weight: 700;
      }
      .brand-mark {
        width: 24px;
        height: 24px;
        display: block;
        object-fit: contain;
      }
      .collection-card,
      .links-card,
      .tracks-card {
        border: 1px solid rgba(255, 255, 255, 0.11);
        border-radius: 12px;
        background: rgba(17, 22, 18, 0.9);
        overflow: hidden;
      }
      .collection-card {
        display: grid;
        grid-template-columns: 138px minmax(0, 1fr);
      }
      .art-stage {
        display: grid;
        min-height: 138px;
        place-items: center;
        padding: 18px;
        background: #151b17;
      }
      .cover,
      .cover-fallback {
        width: 102px;
        aspect-ratio: 1;
        border-radius: 10px;
        border: 1px solid rgba(255, 255, 255, 0.16);
      }
      .cover {
        object-fit: cover;
      }
      .cover-fallback {
        display: grid;
        place-items: center;
        background: #7df49a;
        color: #111411;
        font-size: 42px;
        font-weight: 900;
      }
      .collection-copy {
        min-width: 0;
        padding: 22px 22px 20px 0;
      }
      .collection-kind {
        margin: 0 0 8px;
        color: #9fe7b0;
        font-size: 13px;
        font-weight: 800;
        text-transform: capitalize;
      }
      h1 {
        margin: 0;
        overflow-wrap: anywhere;
        font-size: clamp(24px, 7vw, 34px);
        line-height: 1.08;
        letter-spacing: 0;
      }
      .collection-copy p {
        margin: 8px 0 0;
        color: #bac7bf;
        font-size: 14px;
        line-height: 1.45;
      }
      .actions {
        display: grid;
        grid-template-columns: 1fr 1fr;
        grid-column: 1 / -1;
        border-top: 1px solid rgba(255, 255, 255, 0.09);
      }
      .actions:has(button[hidden]) {
        grid-template-columns: 1fr;
      }
      button {
        min-height: 50px;
        border: 0;
        border-right: 1px solid rgba(255, 255, 255, 0.09);
        background: rgba(255, 255, 255, 0.03);
        color: #9fe7b0;
        font: inherit;
        font-size: 14px;
        font-weight: 800;
        cursor: pointer;
      }
      button:last-child {
        border-right: 0;
      }
      button:hover,
      button:focus-visible {
        background: rgba(125, 244, 154, 0.11);
        outline: none;
      }
      .links-card,
      .tracks-card {
        margin-top: 16px;
        padding: 16px;
      }
      .section-title {
        margin: 0 0 12px;
        color: #f4f8f1;
        font-size: 16px;
        font-weight: 900;
      }
      .services {
        display: grid;
        gap: 9px;
      }
      .service-card {
        display: flex;
        align-items: center;
        gap: 12px;
        min-height: 62px;
        padding: 11px 12px;
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 10px;
        background: rgba(255, 255, 255, 0.045);
        color: #f4f8f1;
        text-decoration: none;
      }
      .service-card:hover,
      .service-card:focus-visible {
        border-color: color-mix(in srgb, var(--service-color), white 18%);
        background: color-mix(in srgb, var(--service-color), transparent 88%);
        outline: none;
      }
      .service-mark {
        width: 40px;
        height: 40px;
        flex: 0 0 auto;
        display: grid;
        place-items: center;
        border-radius: 10px;
        background: rgba(255, 255, 255, 0.96);
        color: #071009;
      }
      .service-mark img {
        width: 24px;
        height: 24px;
        display: block;
      }
      .service-copy {
        min-width: 0;
        display: grid;
        gap: 2px;
      }
      .service-copy strong,
      .service-copy span {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .service-copy strong {
        font-size: 15px;
      }
      .service-copy span,
      .track-artist,
      .track-time {
        color: #aeb9b2;
        font-size: 13px;
      }
      .service-open {
        margin-left: auto;
        color: #bdf4ca;
        font-size: 13px;
        font-weight: 900;
      }
      ol {
        display: grid;
        gap: 1px;
        margin: 0;
        padding: 0;
        list-style: none;
      }
      li {
        display: grid;
        grid-template-columns: 30px minmax(0, 1fr) minmax(0, 150px) 44px;
        align-items: center;
        gap: 10px;
        min-height: 38px;
        border-top: 1px solid rgba(255, 255, 255, 0.07);
      }
      li:first-child {
        border-top: 0;
      }
      .track-index {
        color: #7f8d84;
        font-size: 12px;
        text-align: right;
      }
      .track-title,
      .track-artist {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .track-title {
        font-size: 14px;
        font-weight: 700;
      }
      .footer-note {
        margin: 18px 0 0;
        text-align: center;
        color: #7f8d84;
        font-size: 13px;
      }
      @media (max-width: 520px) {
        main {
          width: min(100vw - 18px, 520px);
          padding-top: 14px;
        }
        .collection-card {
          grid-template-columns: 1fr;
        }
        .collection-copy {
          padding: 0 18px 18px;
          text-align: center;
        }
        li {
          grid-template-columns: 24px minmax(0, 1fr) 40px;
        }
        .track-artist {
          display: none;
        }
      }
    </style>
  </head>
  <body>
    ${artwork ? `<div class="page-backdrop"><img src="${escapeHtml(artwork)}" alt=""></div>` : ''}
    <main>
      <div class="brand"><img class="brand-mark" src="${orchardLogoDataUri}" alt=""><span>Orchard links</span></div>
      <section class="collection-card" aria-labelledby="collection-title">
        <div class="art-stage">
          ${artwork ? `<img class="cover" src="${escapeHtml(artwork)}" alt="">` : `<div class="cover-fallback" aria-hidden="true">${escapeHtml(collection.title.slice(0, 1) || 'O')}</div>`}
        </div>
        <div class="collection-copy">
          <p class="collection-kind">${escapeHtml(collection.kind)}</p>
          <h1 id="collection-title">${escapeHtml(collection.title)}</h1>
          <p>${escapeHtml(subtitle)}</p>
        </div>
        <div class="actions">
          <button type="button" data-copy>Copy link</button>
          <button type="button" data-share>Share</button>
        </div>
      </section>
      <section class="links-card" aria-labelledby="open-title">
        <h2 class="section-title" id="open-title">Open</h2>
        <div class="services">${links}</div>
      </section>
      ${tracks ? `<section class="tracks-card" aria-labelledby="tracks-title"><h2 class="section-title" id="tracks-title">Tracks</h2><ol>${tracks}</ol></section>` : ''}
      <p class="footer-note">Shared with Orchard</p>
    </main>
    <script>
      const copyButton = document.querySelector('[data-copy]');
      const shareButton = document.querySelector('[data-share]');
      const pageTitle = ${scriptJson(title)};

      copyButton?.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(window.location.href);
          copyButton.textContent = 'Copied';
          setTimeout(() => { copyButton.textContent = 'Copy link'; }, 1400);
        } catch {
          copyButton.textContent = 'Copy failed';
          setTimeout(() => { copyButton.textContent = 'Copy link'; }, 1400);
        }
      });

      if (!navigator.share) {
        shareButton.hidden = true;
      } else {
        shareButton.addEventListener('click', async () => {
          await navigator.share({ title: pageTitle, url: window.location.href }).catch(() => {});
        });
      }
    </script>
  </body>
</html>`;
}

function escapeHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function scriptJson(value) {
  return JSON.stringify(String(value || '')).replace(/</g, '\\u003c');
}
