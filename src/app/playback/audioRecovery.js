const maxFailedAudioFormats = 4;
const maxFailedVideoFormats = 4;

function arraySet(values = []) {
  return new Set(values.map(String).filter(Boolean));
}

function mimeFamily(value = '') {
  return String(value).split(';', 1)[0].trim().toLowerCase();
}

export function audioRecoveryPlan(track, options = {}) {
  if (!track || track.mediaKind === 'video' || (!options.refreshStream && !track.itag)) return null;

  const failedItags = arraySet(track.failedAudioItags);
  const failedMimeTypes = arraySet(track.failedAudioMimeTypes);
  if (options.avoidCurrentFormat) {
    if (track.itag) failedItags.add(String(track.itag));
  }
  if (options.avoidCurrentMimeType) {
    const failedMimeFamily = mimeFamily(track.mimeType);
    if (failedMimeFamily) failedMimeTypes.add(failedMimeFamily);
  }
  if (options.avoidCurrentFormat && failedItags.size > maxFailedAudioFormats) return null;

  return {
    track: {
      ...track,
      failedAudioItags: [...failedItags],
      failedAudioMimeTypes: [...failedMimeTypes],
      playbackFallbackTried: failedItags.size > 0,
      streamRefreshTried: options.refreshStream ? true : track.streamRefreshTried
    },
    avoidItags: [...failedItags],
    avoidMimeTypes: [...failedMimeTypes]
  };
}

export function videoRecoveryPlan(track, options = {}) {
  if (!track || track.mediaKind !== 'video' || (!options.refreshStream && !track.itag)) return null;

  const failedItags = arraySet(track.failedVideoItags);
  if (options.avoidCurrentFormat && track.itag) failedItags.add(String(track.itag));
  if (options.avoidCurrentFormat && failedItags.size > maxFailedVideoFormats) return null;

  return {
    track: {
      ...track,
      failedVideoItags: [...failedItags],
      playbackFallbackTried: failedItags.size > 0,
      streamRefreshTried: options.refreshStream ? true : track.streamRefreshTried
    },
    avoidItags: [...failedItags]
  };
}
