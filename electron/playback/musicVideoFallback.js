function durationSeconds(item = {}) {
  const direct = Number(item.durationSeconds || item.duration?.seconds || 0);
  if (direct > 0) return Math.round(direct);

  const parts = String(item.duration?.text || item.duration || '')
    .trim()
    .split(':')
    .map(Number);
  if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
  return parts.reduce((total, part) => total * 60 + part, 0);
}

export function isAgeGateRiskTrack(target = {}) {
  return /^\s*fuck/i.test(target.title || '');
}

export function createMusicVideoFallback({ normalizedLookupText, shelfItems }) {
  return async function findMusicVideoFallback(yt, target = {}) {
    const targetTitle = normalizedLookupText(target.title);
    const targetArtist = normalizedLookupText(target.artist || target.artists?.[0] || '');
    if (!targetTitle) return null;

    const query = [target.title, target.artist || target.artists?.[0]]
      .filter(Boolean)
      .join(' ')
      .trim();
    if (!query) return null;

    try {
      let targetDuration = durationSeconds(target);
      if (!targetDuration) {
        const songSearch = await yt.music.search(query, { type: 'song' });
        const songCandidates = shelfItems(songSearch.songs);
        const matchingSong = songCandidates.find((item) => item.id === target.videoId) ||
          songCandidates.find((item) => {
            const candidateArtist = normalizedLookupText(item.artist || item.artists?.[0] || '');
            return normalizedLookupText(item.title) === targetTitle &&
              (!targetArtist || !candidateArtist || candidateArtist === targetArtist);
          });
        targetDuration = durationSeconds(matchingSong);
      }
      if (!targetDuration) return null;

      const search = await yt.music.search(query, { type: 'video' });
      const candidates = shelfItems(search.videos)
        .filter((item) => {
          const candidateDuration = durationSeconds(item);
          const candidateArtist = normalizedLookupText(item.artist || item.artists?.[0] || '');
          const isVideo = item.type === 'video' || /MUSIC_VIDEO_TYPE_(OMV|UGC)/i.test(item.musicVideoType || '');

          return item.id &&
            item.id !== target.videoId &&
            isVideo &&
            normalizedLookupText(item.title) === targetTitle &&
            (!targetArtist || !candidateArtist || candidateArtist === targetArtist) &&
            candidateDuration > 0 &&
            Math.abs(candidateDuration - targetDuration) <= 5;
        })
        .sort((left, right) =>
          Math.abs(durationSeconds(left) - targetDuration) -
          Math.abs(durationSeconds(right) - targetDuration)
        );

      return candidates[0] || null;
    } catch (error) {
      console.warn(`Could not find music-video fallback for ${target.videoId}: ${error.message}`);
      return null;
    }
  };
}
