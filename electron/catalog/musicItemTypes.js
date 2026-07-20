// Classifies raw YouTube Music renderer payloads without leaking page-type details to callers.
export function musicPageType(item = {}) {
  const payload = item.browsePayload || item.endpoint?.payload || item.on_tap?.payload || {};
  return payload.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType || '';
}

function hasPrivatelyOwnedMarker(value, seen = new Set()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return false;
  seen.add(value);

  return Object.entries(value).some(([key, nested]) => {
    const normalizedKey = key.replaceAll('_', '').toLowerCase();
    if (normalizedKey === 'musicdeleteprivatelyownedentitycommand') return true;
    if (normalizedKey === 'entityid' && /^[a-z]+_po_/i.test(String(nested || ''))) return true;
    if (normalizedKey === 'type' && /deleteprivatelyownedentity/i.test(String(nested || ''))) return true;
    return hasPrivatelyOwnedMarker(nested, seen);
  });
}

export function isUploadedMusicItem(item = {}) {
  if (item.isUpload) return true;
  if (/^[a-z]+_po_/i.test(String(item.entityId || item.entity_id || ''))) return true;
  if (hasPrivatelyOwnedMarker(item.menu)) return true;

  return [
    item.browseId,
    item.browsePayload?.browseId,
    item.albumId,
    item.artistBrowseId,
    ...(item.artistBrowseIds || [])
  ].some((browseId) => /^FEmusic_library_privately_owned_(?:artist|release)/.test(browseId || ''));
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
