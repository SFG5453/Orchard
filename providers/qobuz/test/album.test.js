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

import { albumCandidates, selectQobuzAlbum } from '../src/album.js';
import { createQobuzAuthorizationUrl } from '../src/oauth.js';
import { formEncode, queryParams } from '../src/query.js';

const hiresDeluxe = {
  id: 'deluxe', title: 'Example Album', version: 'Deluxe Edition', artist: { name: 'Example Artist' },
  tracks_count: 16, release_date_original: '2021-05-01',
  maximum_bit_depth: 24, maximum_sampling_rate: 96, hires_streamable: true, streamable: true
};
const cdStandard = {
  id: 'standard', title: 'Example Album', artist: { name: 'Example Artist' },
  tracks_count: 12, release_date_original: '2021-05-01',
  maximum_bit_depth: 16, maximum_sampling_rate: 44.1, hires_streamable: false, streamable: true
};

test('album matching prefers the exact edition and reports its quality', () => {
  const target = { title: 'Example Album (Deluxe Edition)', artist: 'Example Artist', trackCount: 16 };
  const match = selectQobuzAlbum(target, [cdStandard, hiresDeluxe]);
  assert.equal(match.albumId, 'deluxe');
  assert.equal(match.tier, 'hires');
  assert.equal(match.bitDepth, 24);
  assert.equal(match.sampleRate, 96000);
});

test('album matching ignores edition tags only when nothing matches exactly', () => {
  const target = { title: 'Example Album', artist: 'Example Artist', trackCount: 12 };
  assert.equal(selectQobuzAlbum(target, [hiresDeluxe, cdStandard]).albumId, 'standard');
  assert.equal(selectQobuzAlbum(target, [hiresDeluxe]).albumId, 'deluxe');
});

test('album quality follows the CD lossless setting', () => {
  const target = { title: 'Example Album (Deluxe Edition)', artist: 'Example Artist' };
  const match = selectQobuzAlbum(target, [hiresDeluxe], 'lossless');
  assert.equal(match.tier, 'lossless');
  assert.equal(match.bitDepth, 16);
});

test('album matching rejects other artists and unstreamable albums', () => {
  const target = { title: 'Example Album', artist: 'Someone Else' };
  assert.equal(selectQobuzAlbum(target, [cdStandard]), null);
  const locked = { ...cdStandard, streamable: false };
  assert.equal(selectQobuzAlbum({ title: 'Example Album', artist: 'Example Artist' }, [locked]), null);
  assert.deepEqual(albumCandidates({ albums: { items: [cdStandard, {}] } }), [cdStandard]);
});

test('query helpers stand in for URL and URLSearchParams', () => {
  assert.equal(formEncode({ query: 'a b&c', limit: 20 }), 'query=a+b%26c&limit=20');
  assert.deepEqual(queryParams('https://x.test/s?Expires=1700000000&e=a+b#frag'), { Expires: '1700000000', e: 'a b' });
  const url = createQobuzAuthorizationUrl({ appId: '123', redirectUrl: 'http://127.0.0.1:5/cb' });
  assert.equal(url, 'https://www.qobuz.com/signin/oauth?ext_app_id=123&redirect_url=http%3A%2F%2F127.0.0.1%3A5%2Fcb');
});
