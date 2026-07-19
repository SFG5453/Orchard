function normalizedKey(value) {
  return String(value || '').trim().toLowerCase();
}

function playableTracks(detail) {
  return (detail?.tracks || []).filter((track) => track?.id && track?.thumbnail);
}

function albumKey(track) {
  return normalizedKey(track.albumId) ||
    normalizedKey(track.album) ||
    normalizedKey(track.thumbnail);
}

export function playlistArtworkSeedTracks(detail) {
  if (detail?.kind !== 'playlist') return [];

  const seenAlbums = new Set();
  const seeds = [];

  for (const track of playableTracks(detail)) {
    const key = albumKey(track);
    if (key && seenAlbums.has(key)) continue;
    if (key) seenAlbums.add(key);
    seeds.push(track);
    if (seeds.length === 4) break;
  }

  return seeds;
}

export function playlistArtworkDetection(detail) {
  const seedTracks = playlistArtworkSeedTracks(detail);
  const canUseGeneratedCover = detail?.kind === 'playlist' &&
    Boolean(detail.thumbnail) &&
    seedTracks.length === 4;

  return {
    canUseGeneratedCover,
    seedTracks
  };
}
