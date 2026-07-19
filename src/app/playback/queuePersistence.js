const PLAYBACK_STATE_STORAGE_KEY = 'orchard:playback-state';
const MAX_STORED_TRACKS = 80;

function storageAvailable() {
  return typeof window !== 'undefined' && Boolean(window.localStorage);
}

export function clampVolume(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0.85;
  return Math.max(0, Math.min(1, number));
}

export function normalizeRepeatMode(value) {
  return ['off', 'queue', 'one'].includes(value) ? value : 'off';
}

export function sanitizedTrack(track) {
  if (!track?.id) return null;

  const {
    streamUrl,
    audioStreamUrl,
    playbackFallbackTried,
    streamRefreshTried,
    failedAudioItags,
    failedAudioMimeTypes,
    failedVideoItags,
    itag,
    audioItag,
    mimeType,
    ...storedTrack
  } = track;

  return storedTrack;
}

function sanitizedTrackList(items = []) {
  const seen = new Set();

  return items
    .map(sanitizedTrack)
    .filter((track) => {
      if (!track?.id || seen.has(track.id)) return false;
      seen.add(track.id);
      return true;
    })
    .slice(0, MAX_STORED_TRACKS);
}

export function readPlaybackState() {
  if (!storageAvailable()) {
    return { activeTrack: null, queue: [], history: [], shuffleSourceQueue: [] };
  }

  try {
    const parsed = JSON.parse(window.localStorage.getItem(PLAYBACK_STATE_STORAGE_KEY) || '{}');
    return {
      activeTrack: sanitizedTrack(parsed.activeTrack),
      queue: sanitizedTrackList(parsed.queue),
      history: sanitizedTrackList(parsed.history),
      shuffleSourceQueue: sanitizedTrackList(parsed.shuffleSourceQueue)
    };
  } catch {
    return { activeTrack: null, queue: [], history: [], shuffleSourceQueue: [] };
  }
}

export function clearPlaybackState() {
  if (!storageAvailable()) return;

  try {
    window.localStorage.removeItem(PLAYBACK_STATE_STORAGE_KEY);
  } catch {
    // Clearing stored playback state is best-effort.
  }
}

export function writePlaybackState({ activeTrack, queue, history, shuffleSourceQueue }) {
  if (!storageAvailable()) return;

  try {
    window.localStorage.setItem(PLAYBACK_STATE_STORAGE_KEY, JSON.stringify({
      activeTrack: sanitizedTrack(activeTrack),
      queue: sanitizedTrackList(queue),
      history: sanitizedTrackList(history),
      shuffleSourceQueue: sanitizedTrackList(shuffleSourceQueue)
    }));
  } catch {
    // Restoring the queue is useful, but storage failures should never block playback.
  }
}
