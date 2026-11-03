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

import { createAppleMusic } from '../src/index.js';
import { item } from '../src/normalize.js';

const art = { url: 'https://is1.mzstatic.com/image/{w}x{h}{c}.{f}', width: 3000, height: 3000 };

function appleMusic(routes) {
  const requests = [];
  const client = createAppleMusic({
    fetchImpl: async (url, init = {}) => {
      requests.push({ url, method: init.method || 'GET' });
      const route = routes.find(([pattern]) => pattern.test(url));
      if (!route) return { ok: false, status: 404, json: async () => ({}), text: async () => '' };
      const body = route[2];
      return { ok: route[1] < 400, status: route[1], json: async () => body, text: async () => JSON.stringify(body) };
    },
    session: () => ({ developerToken: 'dev', userToken: 'user', storefront: 'us' })
  });
  return { client, requests };
}

const song = (id, name) => ({
  id, type: 'songs',
  attributes: { name, artistName: 'Usher', durationInMillis: 200000, artwork: art, playParams: { id, kind: 'song' } }
});
const stationResource = (id, name, isLive = false) => ({
  id, type: 'stations', attributes: { name, isLive, artwork: art, playParams: { id, kind: 'radioStation' } }
});

test('track stations become playlist cards and live radio is skipped', () => {
  assert.deepEqual(item(stationResource('ra.1', 'Come Thru Station')), {
    type: 'playlist', station: true, browseId: 'am.stations.ra.1', source: 'apple_music',
    title: 'Come Thru Station', artist: 'Apple Music', subtitle: 'Station',
    thumbnail: 'https://is1.mzstatic.com/image/600x600bb.jpg', description: ''
  });
  assert.equal(item(stationResource('ra.978194965', 'Apple Music 1', true)), null);
});

test('up next for a song reads the song station', async () => {
  const { client, requests } = appleMusic([
    [/\/v1\/me\/stations\/next-tracks\/ra\.42\?limit=10$/, 200, { data: [song('42', 'Seed'), song('7', 'Yeah!')] }]
  ]);
  const tracks = await client.upNext('am.songs.42');
  assert.deepEqual(tracks.map((entry) => entry.id), ['am.songs.42', 'am.songs.7']);
  assert.equal(requests[0].method, 'POST');
});

test('a song without a station has no recommendations', async () => {
  const { client } = appleMusic([]);
  assert.deepEqual(await client.upNext('am.songs.42'), []);
});

test('station pages hold the next batch as numbered tracks', async () => {
  const { client } = appleMusic([
    [/\/v1\/catalog\/us\/stations\/ra\.u-abc$/, 200, { data: [stationResource('ra.u-abc', 'Your Station')] }],
    [/\/v1\/me\/stations\/next-tracks\/ra\.u-abc\?limit=10$/, 200, { data: [song('1', 'One'), song('2', 'Two')] }]
  ]);
  const page = await client.stationPage('am.stations.ra.u-abc');
  assert.equal(page.kind, 'playlist');
  assert.equal(page.station, true);
  assert.equal(page.title, 'Your Station');
  assert.deepEqual(page.tracks.map((entry) => [entry.id, entry.index]), [['am.songs.1', '1'], ['am.songs.2', '2']]);
});

test('live stations do not open as pages', async () => {
  const { client } = appleMusic([
    [/\/v1\/catalog\/us\/stations\/ra\.9$/, 200, { data: [stationResource('ra.9', 'Apple Music 1', true)] }],
    [/next-tracks/, 200, { data: [] }]
  ]);
  await assert.rejects(client.stationPage('am.stations.ra.9'), { code: 'live_station' });
});

test('station songs get artist and album links from the catalog', async () => {
  const credited = {
    ...song('7', 'Yeah!'),
    relationships: {
      artists: { data: [{ id: '1', type: 'artists', attributes: { name: 'Usher' } }] },
      albums: { data: [{ id: '9', type: 'albums' }] }
    }
  };
  const { client } = appleMusic([
    [/next-tracks\/ra\.42/, 200, { data: [song('7', 'Yeah!'), song('8', 'Gone')] }],
    [/\/v1\/catalog\/us\/songs\?ids=7%2C8&include=artists%2Calbums$/, 200, { data: [credited] }]
  ]);
  const [first, second] = await client.upNext('am.songs.42');
  assert.deepEqual(first.artistBrowseIds, ['am.artists.1']);
  assert.equal(first.albumId, 'am.albums.9');
  assert.deepEqual(second.artistBrowseIds, []);
});
