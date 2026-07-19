function cleanText(value = '') {
  return String(value || '').trim();
}

function comparableText(value = '') {
  return cleanText(value)
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function looseTextMatches(left = '', right = '') {
  const normalizedLeft = comparableText(left);
  const normalizedRight = comparableText(right);
  if (!normalizedLeft || !normalizedRight) return true;
  return normalizedLeft === normalizedRight ||
    normalizedLeft.includes(normalizedRight) ||
    normalizedRight.includes(normalizedLeft);
}

function responseItem(data) {
  return Array.isArray(data) ? data[0] : data;
}

export function artworkSearchUrl(provider, target, artist) {
  const providerId = cleanText(provider?.id);
  const baseUrl = cleanText(provider?.baseUrl);
  const title = cleanText(target?.title);
  const album = cleanText(target?.album);
  const durationSeconds = Number(target?.durationSeconds || 0);
  const resolvedArtist = cleanText(artist);
  if (!providerId || !baseUrl || !title || !resolvedArtist) return null;

  const url = new URL(baseUrl);
  if (providerId === 'm8tec') {
    if (!album) return null;
    url.pathname = '/api/v1/artwork/search';
    url.searchParams.set('artist', resolvedArtist);
    url.searchParams.set('album', album);
    url.searchParams.set('title', title);
    return url;
  }

  url.searchParams.set('s', title);
  url.searchParams.set('a', resolvedArtist);
  if (providerId === 'orchard') {
    if (album) url.searchParams.set('albumName', album);
    if (durationSeconds > 0) url.searchParams.set('duration', String(Math.round(durationSeconds)));
  }
  return url;
}

export function artworkAlbumIdUrl(provider, albumId) {
  const providerId = cleanText(provider?.id);
  const baseUrl = cleanText(provider?.baseUrl);
  const id = cleanText(albumId);
  if (!providerId || !baseUrl || !/^\d+$/.test(id)) return null;

  const url = new URL(baseUrl);
  if (providerId === 'm8tec') {
    url.pathname = '/api/v1/artwork/url';
    url.searchParams.set('url', `https://music.apple.com/us/album/${id}`);
    return url;
  }

  url.searchParams.set('id', id);
  return url;
}

export function normalizeArtworkProviderResponse(provider, data, target = null, fallbackArtist = '', albumId = '') {
  const item = responseItem(data);
  if (!item || item.error) return null;
  if (provider?.id !== 'm8tec') return item;

  const artist = cleanText(item.artist);
  const album = cleanText(item.album);
  const targetAlbum = cleanText(target?.album);
  const expectedArtist = cleanText(fallbackArtist);
  if (!looseTextMatches(artist, expectedArtist) || !looseTextMatches(album, targetAlbum)) return null;

  const animated = cleanText(item.url || item.url_tall);
  if (!animated) return null;

  return {
    name: cleanText(target?.title) || album,
    artist: artist || expectedArtist,
    albumId: cleanText(albumId),
    static: '',
    animated,
    animatedVertical: cleanText(item.url_tall),
    videoUrl: ''
  };
}
