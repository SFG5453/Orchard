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
import {
  isOfficialArtistChannel,
  subscribedArtistFromChannel,
  subscribedArtistsFromChannels
} from '../electron/catalog/subscribedArtists.js';

function channel({ id, name, artist = false, badge = '' }) {
  return {
    id,
    author: {
      id,
      name,
      is_verified_artist: artist,
      badges: badge ? [{ tooltip: badge }] : [],
      thumbnails: [{ url: `${id}-small.jpg` }, { url: `${id}-large.jpg` }]
    }
  };
}

test('recognizes official artist subscriptions without accepting ordinary verified channels', () => {
  assert.equal(isOfficialArtistChannel(channel({ id: 'artist', name: 'Artist', artist: true })), true);
  assert.equal(isOfficialArtistChannel(channel({ id: 'badge', name: 'Badge Artist', badge: 'Official Artist Channel' })), true);
  assert.equal(isOfficialArtistChannel(channel({ id: 'creator', name: 'Creator', badge: 'Verified' })), false);
});

test('normalizes and deduplicates subscribed artists', () => {
  const artist = channel({ id: 'artist', name: 'Artist', artist: true });
  assert.deepEqual(subscribedArtistFromChannel(artist), {
    name: 'Artist',
    title: 'Artist',
    browseId: 'artist',
    type: 'artist',
    thumbnail: 'artist-large.jpg',
    subtitle: 'Subscribed on YouTube'
  });
  assert.deepEqual(subscribedArtistsFromChannels([
    artist,
    artist,
    channel({ id: 'creator', name: 'Creator', badge: 'Verified' })
  ]), [subscribedArtistFromChannel(artist)]);
});
