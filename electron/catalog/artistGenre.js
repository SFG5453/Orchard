// Resolves best-effort artist genre metadata without coupling catalog callers to iTunes responses.
const ITUNES_API_ORIGIN = 'https://itunes.apple.com';
const MAX_RETRIES = 3;

function cleanText(value, maximum = 220) {
  return String(value || '').trim().replace(/\s+/g, ' ').slice(0, maximum);
}

function normalizeText(value) {
  return cleanText(value)
    .normalize('NFKD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function releaseKey(value) {
  return normalizeText(value)
    .replace(/\b(deluxe|expanded|anniversary|remaster(?:ed)?|bonus|edition|version)\b/g, ' ')
    .replace(/\b(single|ep)\b$/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function releaseMatches(candidate, requested) {
  const candidateKey = releaseKey(candidate);
  const requestedKey = releaseKey(requested);
  if (!candidateKey || !requestedKey) return false;
  return candidateKey === requestedKey ||
    candidateKey.startsWith(`${requestedKey} `) ||
    requestedKey.startsWith(`${candidateKey} `);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function createArtistGenreResolver({ fetcher = fetch } = {}) {
  const cache = new Map();

  async function fetchItunesJson(pathname, params = {}) {
    const url = new URL(pathname, ITUNES_API_ORIGIN);
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, value);
    }

    for (let attempt = 0; attempt < MAX_RETRIES; attempt += 1) {
      const response = await fetcher(url, {
        headers: {
          accept: 'application/json',
          'user-agent': 'Orchard Artist Metadata/2.1'
        }
      });
      if (response.ok) return response.json();
      if (response.status !== 429 || attempt === MAX_RETRIES - 1) {
        throw new Error(`iTunes Search API returned ${response.status}`);
      }

      const retryAfter = Number(response.headers.get('retry-after'));
      const waitMs = Number.isFinite(retryAfter) && retryAfter > 0
        ? Math.min(retryAfter * 1000, 2000)
        : 400 * (attempt + 1);
      await delay(waitMs);
    }

    throw new Error('iTunes Search API retry limit reached.');
  }

  async function resolveArtistGenre(payload = {}) {
    const artist = cleanText(payload.artist, 160);
    const album = cleanText(payload.album);
    if (!artist || !album) throw new Error('Artist and album are required.');

    const key = `${normalizeText(artist)}:${releaseKey(album)}`;
    if (cache.has(key)) return cache.get(key);

    const artistPayload = await fetchItunesJson('/search', {
      term: artist,
      country: 'US',
      media: 'music',
      entity: 'musicArtist',
      limit: 10
    });
    const albumPayload = await fetchItunesJson('/search', {
      term: `${artist} ${album}`,
      country: 'US',
      media: 'music',
      entity: 'album',
      limit: 25
    });

    const artistKey = normalizeText(artist);
    const matchingAlbums = (albumPayload.results || []).filter((item) =>
      item?.artistId &&
      normalizeText(item.artistName) === artistKey &&
      releaseMatches(item.collectionName, album)
    );
    const candidates = (artistPayload.results || [])
      .filter((item) => normalizeText(item.artistName) === artistKey)
      .map((item) => {
        const confirmation = matchingAlbums.find((albumItem) => Number(albumItem.artistId) === Number(item.artistId));
        const genre = cleanText(item.primaryGenreName || confirmation?.primaryGenreName, 100);
        return {
          artistId: item.artistId,
          artistName: cleanText(item.artistName, 160),
          genre,
          confirmedByAlbum: Boolean(confirmation),
          confirmedAlbum: cleanText(confirmation?.collectionName),
          confidence: confirmation ? 1 : 0.35,
          score: 100 + (genre ? 30 : 0) + (confirmation ? 250 : 0)
        };
      })
      .sort((left, right) => right.score - left.score || Number(left.artistId) - Number(right.artistId));

    const winner = candidates[0];
    const result = winner?.confirmedByAlbum && winner.genre
      ? {
          ok: true,
          matched: true,
          genre: winner.genre,
          providerArtistId: String(winner.artistId),
          confirmedAlbum: winner.confirmedAlbum || album,
          confidence: winner.confidence
        }
      : {
          ok: true,
          matched: false,
          genre: '',
          providerArtistId: '',
          confirmedAlbum: '',
          confidence: 0
        };

    cache.set(key, result);
    return result;
  }

  return { resolveArtistGenre };
}
