import { sanitizedTrack } from '../playback/queuePersistence.js';

const PINS_STORAGE_KEY = 'orchard:pinned-tracks';
const MAX_PINNED_TRACKS = 200;

export function readPinnedTracks() {
  if (typeof window === 'undefined') return [];

  try {
    const parsed = JSON.parse(window.localStorage.getItem(PINS_STORAGE_KEY) || '[]');
    const seen = new Set();

    return (Array.isArray(parsed) ? parsed : [])
      .map(sanitizedTrack)
      .filter((track) => {
        if (!track?.id || seen.has(track.id)) return false;
        seen.add(track.id);
        return true;
      })
      .slice(0, MAX_PINNED_TRACKS);
  } catch {
    return [];
  }
}

export function writePinnedTracks(tracks = []) {
  if (typeof window === 'undefined') return;

  try {
    window.localStorage.setItem(
      PINS_STORAGE_KEY,
      JSON.stringify(tracks.map(sanitizedTrack).filter(Boolean).slice(0, MAX_PINNED_TRACKS))
    );
  } catch {
    // Pins are a convenience; storage failures should not interrupt playback.
  }
}
