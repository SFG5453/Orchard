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
  normalizeText,
  rankArtistCandidates,
  releaseKey,
  releaseMatches
} from '../src/index.js';

test('normalizes artist and release names for matching', () => {
  assert.equal(normalizeText('Beyoncé & JAY-Z'), 'beyonce and jay z');
  assert.equal(releaseKey('SOS (Deluxe Edition)'), 'sos');
  assert.equal(releaseMatches('SOS (Deluxe Edition)', releaseKey('SOS')), true);
});

test('requires an album-confirmed exact-name candidate', () => {
  const artists = [
    { artistId: 605800394, artistName: 'SZA', primaryGenreName: 'R&B/Soul', primaryGenreId: 15 },
    { artistId: 1889121890, artistName: 'SZA', primaryGenreName: null, primaryGenreId: null }
  ];
  const albums = [
    { artistId: 605800394, artistName: 'SZA', collectionName: 'SOS', primaryGenreName: 'R&B/Soul' }
  ];

  const result = rankArtistCandidates(artists, albums, 'SZA', 'SOS')[0];
  assert.equal(result.artistId, 605800394);
  assert.equal(result.genre, 'R&B/Soul');
  assert.equal(result.confirmedByAlbum, true);
  assert.equal(result.confidence, 1);
});

test('does not confirm a same-name artist whose album belongs to another id', () => {
  const result = rankArtistCandidates(
    [{ artistId: 2, artistName: 'Example', primaryGenreName: 'Pop' }],
    [{ artistId: 1, artistName: 'Example', collectionName: 'First Light' }],
    'Example',
    'First Light'
  )[0];

  assert.equal(result.confirmedByAlbum, false);
});
