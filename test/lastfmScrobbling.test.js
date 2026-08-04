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

import { lastfmTrackPayload, shouldScrobble } from '../src/app/social/lastfmScrobbling.js';

test('Last.fm track payload uses structured Orchard metadata', () => {
  assert.deepEqual(lastfmTrackPayload({
    title: ' Song ',
    artists: [{ name: 'Artist' }],
    album: 'Album',
    durationSeconds: 181
  }), {
    title: 'Song',
    artist: 'Artist',
    album: 'Album',
    albumArtist: '',
    duration: 181
  });
});

test('Last.fm eligibility follows the half-track or four-minute rule', () => {
  assert.equal(shouldScrobble(30, 30), false);
  assert.equal(shouldScrobble(180, 89), false);
  assert.equal(shouldScrobble(180, 90), true);
  assert.equal(shouldScrobble(600, 239), false);
  assert.equal(shouldScrobble(600, 240), true);
});
