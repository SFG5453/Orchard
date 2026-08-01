import { clearSessionValue, readSessionValue, writeSessionValue } from '../core/sessionStore.js';

const PLAYBACK_STATE_STORAGE_KEY = 'orchard:playback-state';
const MAX_STORED_TRACKS = 80;

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
  const parsed = readSessionValue(PLAYBACK_STATE_STORAGE_KEY);
  if (!parsed || typeof parsed !== 'object') {
    return { activeTrack: null, queue: [], history: [], shuffleSourceQueue: [] };
  }

  return {
    activeTrack: sanitizedTrack(parsed.activeTrack),
    queue: sanitizedTrackList(parsed.queue),
    history: sanitizedTrackList(parsed.history),
    shuffleSourceQueue: sanitizedTrackList(parsed.shuffleSourceQueue)
  };
}

export function clearPlaybackState() {
  clearSessionValue(PLAYBACK_STATE_STORAGE_KEY);
}

export function writePlaybackState({ activeTrack, queue, history, shuffleSourceQueue }) {
  writeSessionValue(PLAYBACK_STATE_STORAGE_KEY, {
    activeTrack: sanitizedTrack(activeTrack),
    queue: sanitizedTrackList(queue),
    history: sanitizedTrackList(history),
    shuffleSourceQueue: sanitizedTrackList(shuffleSourceQueue)
  });
}
