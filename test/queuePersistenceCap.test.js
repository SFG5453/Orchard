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

import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createSessionStateStore, readSessionState } from '../electron/main/sessionState.js';

// A track with the fields sanitizedTrack keeps, sized like a real YouTube Music
// entry: long title, artwork URL, credited artists, queue origin.
function realisticTrack(index) {
  return {
    id: `videoId${String(index).padStart(4, '0')}`,
    title: 'Some Reasonably Long Song Title (Deluxe Remastered Version)',
    artist: 'An Artist Name With Several Words',
    artists: [{ name: 'An Artist Name With Several Words', id: 'UCabcdefghijklmnop12345' }],
    album: 'An Album Title That Goes On A While',
    albumId: 'MPREb_abcdefghijk',
    duration: '4:13',
    thumbnail: 'https://lh3.googleusercontent.com/abcdefghijklmnopqrstuvwxyz1234567890ABCDEFGH=w544-h544-l90-rj',
    mediaKind: 'audio',
    explicit: false,
    type: 'song',
    queueOrigin: { kind: 'playlist', title: 'A Playlist With A Long Name', artist: '', totalTrackCount: 2500 }
  };
}

function tracks(count) {
  return Array.from({ length: count }, (_, index) => realisticTrack(index));
}

test('a full shuffled queue at the storage cap survives the main process byte limit', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'orchard-session-'));
  const filePath = path.join(directory, 'session-state.json');

  try {
    const store = createSessionStateStore({ filePath, writeDelayMs: 5000 });
    // Worst case: shuffle on, so the queue and its pre-shuffle order are both
    // stored at full length.
    const payload = {
      activeTrack: realisticTrack(0),
      queue: tracks(2500),
      history: tracks(30),
      shuffleSourceQueue: tracks(2500)
    };

    assert.equal(
      store.set('orchard:playback-state', JSON.stringify(payload)),
      true,
      'the write must be accepted -- an oversize value is dropped whole, not trimmed'
    );

    assert.equal(store.flush(), true);
    const restored = JSON.parse(readSessionState(filePath)['orchard:playback-state']);
    assert.equal(restored.queue.length, 2500, 'the queue comes back at full length');
    assert.equal(restored.shuffleSourceQueue.length, 2500);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
