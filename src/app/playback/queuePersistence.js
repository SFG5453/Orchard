/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { clearSessionValue, readSessionValue, writeSessionValue } from '../core/sessionStore.js';

const PLAYBACK_STATE_STORAGE_KEY = 'orchard:playback-state';
// Enough to restore a shuffled playlist as a shuffled playlist. The old cap of
// 80 predates whole-playlist shuffle, when the queue itself never held more
// than a hundred tracks; restoring 80 out of a few thousand now means coming
// back to a queue that ends long before the playlist does.
//
// Raising this raises the serialized payload with it -- roughly 555 bytes per
// track, doubled while shuffle is on because the pre-shuffle order is stored
// alongside. MAX_VALUE_BYTES in electron/main/sessionState.js has to stay ahead
// of it or the main process rejects the write and the session is lost outright.
const MAX_STORED_TRACKS = 2500;

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

// Stops at the cap rather than sanitizing the whole list and throwing the tail
// away: a shuffled playlist queue now holds every track it will ever play, and
// this runs on every change to it.
function sanitizedTrackList(items = []) {
  const seen = new Set();
  const stored = [];

  for (const item of items) {
    if (stored.length >= MAX_STORED_TRACKS) break;
    const track = sanitizedTrack(item);
    if (!track?.id || seen.has(track.id)) continue;
    seen.add(track.id);
    stored.push(track);
  }

  return stored;
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
