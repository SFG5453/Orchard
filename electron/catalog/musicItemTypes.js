// Classifies raw YouTube Music renderer payloads without leaking page-type details to callers.
export function musicPageType(item = {}) {
  const payload = item.browsePayload || item.endpoint?.payload || item.on_tap?.payload || {};
  return payload.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType || '';
}

export function isKnownArtistItem(item = {}) {
  const pageType = musicPageType(item);
  const browseId = item.browsePayload?.browseId || item.browseId || item.endpoint?.payload?.browseId || '';
  return pageType === 'MUSIC_PAGE_TYPE_ARTIST' ||
    pageType === 'MUSIC_PAGE_TYPE_LIBRARY_ARTIST' ||
    item.is_verified_artist === true ||
    item.author?.is_verified_artist === true ||
    browseId.startsWith('FEmusic_library_privately_owned_artist');
}

export function shouldShowMusicItem(item = {}) {
  const pageType = musicPageType(item);
  const artistLike = item.type === 'artist' || item.type === 'library_artist' ||
    ['MUSIC_PAGE_TYPE_ARTIST', 'MUSIC_PAGE_TYPE_LIBRARY_ARTIST', 'MUSIC_PAGE_TYPE_USER_CHANNEL'].includes(pageType);
  return !artistLike || isKnownArtistItem(item);
}

export function releaseTypeFromText(...values) {
  const text = values.filter(Boolean).join(' ');
  const trackCount = Number(text.match(/(\d+)\s+(?:songs?|tracks?)\b/i)?.[1] || 0);
  if (trackCount >= 7) return 'Album';
  if (/(?:^|[^\w])ep(?:[^\w]|$)/i.test(text)) return 'EP';
  if (/(?:^|[^\w])single(?:[^\w]|$)/i.test(text)) return 'Single';
  return 'Album';
}
