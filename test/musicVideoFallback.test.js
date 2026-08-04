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
import { createMusicVideoFallback, isAgeGateRiskTrack } from '../electron/playback/musicVideoFallback.js';

const normalizedLookupText = (value = '') => String(value).trim().toLowerCase();
const shelfItems = (shelf) => shelf?.items || [];

test('finds a duration-matched video when a standalone track has no duration', async () => {
  const searches = [];
  const yt = {
    music: {
      async search(query, options) {
        searches.push([query, options.type]);
        if (options.type === 'song') {
          return {
            songs: { items: [{
              id: 'song-id',
              title: 'Standalone Song',
              artist: 'The Artist',
              duration: { seconds: 213 }
            }] }
          };
        }
        return {
          videos: { items: [
            {
              id: 'wrong-duration',
              type: 'video',
              title: 'Standalone Song',
              artist: 'The Artist',
              duration: { seconds: 240 }
            },
            {
              id: 'matching-video',
              type: 'video',
              title: 'Standalone Song',
              artist: 'The Artist',
              duration: { seconds: 215 }
            }
          ] }
        };
      }
    }
  };
  const findFallback = createMusicVideoFallback({ normalizedLookupText, shelfItems });

  const fallback = await findFallback(yt, {
    videoId: 'song-id',
    title: 'Standalone Song',
    artist: 'The Artist'
  });

  assert.equal(fallback?.id, 'matching-video');
  assert.deepEqual(searches, [
    ['Standalone Song The Artist', 'song'],
    ['Standalone Song The Artist', 'video']
  ]);
});

test('chooses the closest video within the five-second duration window', async () => {
  const yt = { music: { search: async () => ({
    videos: { items: [
      { id: 'four-away', type: 'video', title: 'Album Song', artist: 'The Artist', durationSeconds: 184 },
      { id: 'one-away', type: 'video', title: 'Album Song', artist: 'The Artist', durationSeconds: 181 }
    ] }
  }) } };
  const findFallback = createMusicVideoFallback({ normalizedLookupText, shelfItems });

  const fallback = await findFallback(yt, {
    videoId: 'song-id',
    title: 'Album Song',
    artist: 'The Artist',
    durationSeconds: 180
  });

  assert.equal(fallback?.id, 'one-away');
});

test('rejects videos with a different title, artist, or duration', async () => {
  const yt = { music: { search: async () => ({
    videos: { items: [
      { id: 'wrong-title', type: 'video', title: 'Album Song Live', artist: 'The Artist', durationSeconds: 180 },
      { id: 'wrong-artist', type: 'video', title: 'Album Song', artist: 'Another Artist', durationSeconds: 180 },
      { id: 'wrong-duration', type: 'video', title: 'Album Song', artist: 'The Artist', durationSeconds: 186 }
    ] }
  }) } };
  const findFallback = createMusicVideoFallback({ normalizedLookupText, shelfItems });

  const fallback = await findFallback(yt, {
    videoId: 'song-id',
    title: 'Album Song',
    artist: 'The Artist',
    durationSeconds: 180
  });

  assert.equal(fallback, null);
});

test('recognizes the legacy proactive age-gate title risk', () => {
  assert.equal(isAgeGateRiskTrack({ title: 'Fuck Ya!' }), true);
  assert.equal(isAgeGateRiskTrack({ title: 'Ordinary Song' }), false);
});
