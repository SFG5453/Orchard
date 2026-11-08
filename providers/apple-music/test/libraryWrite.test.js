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

// Routes match "METHOD url"; a null body answers 204 with no content.
function appleMusic(routes) {
  const requests = [];
  const client = createAppleMusic({
    fetchImpl: async (url, init = {}) => {
      const method = init.method || 'GET';
      requests.push({ method, url, headers: init.headers || {}, body: init.body ? JSON.parse(init.body) : undefined });
      const route = routes.find(([pattern]) => pattern.test(`${method} ${url}`));
      assert.ok(route, `Unexpected ${method} ${url}`);
      const [, status, body] = route;
      const text = body === null ? '' : JSON.stringify(body);
      return { ok: status < 400, status, json: async () => JSON.parse(text), text: async () => text };
    },
    session: () => ({ developerToken: 'dev', userToken: 'user', storefront: 'us' })
  });
  return { client, requests };
}

const catalogTrack = { id: 'am.songs.111', appleId: '111', title: 'Kill Bill', artist: 'SZA' };

test('loving a song rates it and saves it to the library', async () => {
  const { client, requests } = appleMusic([
    [/^PUT .*\/v1\/me\/ratings\/songs\/111$/, 200, { data: [] }],
    [/^POST .*\/v1\/me\/library\?ids%5Bsongs%5D=111$/, 202, null]
  ]);
  assert.deepEqual(await client.setLike(catalogTrack, true), { liked: true, songId: '111' });
  assert.deepEqual(requests[0].body, { type: 'rating', attributes: { value: 1 } });
  assert.ok(requests.every((request) => request.headers['Music-User-Token'] === 'user'));
});

test('unloving deletes the rating and leaves the library alone', async () => {
  const { client, requests } = appleMusic([[/^DELETE .*\/v1\/me\/ratings\/library-songs\/i\.abc$/, 204, null]]);
  assert.deepEqual(await client.setLike({ id: 'am.songs.i.abc', title: 'Demo' }, false), { liked: false, songId: 'i.abc' });
  assert.equal(requests.length, 1);
});

test('like status reads the rating and treats a missing rating as not loved', async () => {
  const { client } = appleMusic([
    [/^GET .*\/ratings\/songs\/111$/, 200, { data: [{ attributes: { value: 1 } }] }],
    [/^GET .*\/ratings\/songs\/222$/, 404, {}]
  ]);
  assert.equal((await client.likeStatus(catalogTrack)).liked, true);
  assert.equal((await client.likeStatus({ id: 'am.songs.222' })).liked, false);
});

test('a YouTube song is matched to the catalog before it is written', async () => {
  const { client, requests } = appleMusic([
    [/^GET .*\/v1\/catalog\/us\/search\?term=Kill%20Bill%20SZA/, 200, { results: { songs: { data: [
      { id: '111', type: 'songs', attributes: { name: 'Kill Bill', artistName: 'SZA' } }
    ] } } }],
    [/^POST .*\/v1\/me\/library\/playlists\/p\.mine\/tracks$/, 204, null]
  ]);
  const result = await client.addToPlaylist({ id: 'dQw4w9WgXcQ', title: 'Kill Bill (Official Video)', artist: 'SZA - Topic' }, 'p.mine', 'Mine');
  assert.deepEqual(result, { playlistId: 'p.mine', title: 'Mine' });
  assert.deepEqual(requests.at(-1).body, { data: [{ id: '111', type: 'songs' }] });
});

test('a song Apple does not have is an error rather than a silent miss', async () => {
  const { client } = appleMusic([[/search/, 200, { results: {} }]]);
  await assert.rejects(client.setLike({ id: 'x', title: 'Nope', artist: 'Nobody' }, true), { code: 'not_found' });
});

test('playlist targets are only the listener\'s own playlists', async () => {
  const { client } = appleMusic([[/^GET .*\/v1\/me\/library\/playlists\?limit=100$/, 200, { data: [
    { id: 'p.mine', type: 'library-playlists', attributes: { name: 'Mine', canEdit: true } },
    { id: 'p.saved', type: 'library-playlists', attributes: { name: 'Today\'s Hits', canEdit: false } }
  ] }]]);
  assert.deepEqual(await client.playlistTargets(), [{ id: 'p.mine', title: 'Mine', thumbnail: '', containsTrack: false }]);
});

test('new playlists start empty or with the seed song', async () => {
  const { client, requests } = appleMusic([[/^POST .*\/v1\/me\/library\/playlists$/, 201, { data: [{ id: 'p.new', attributes: { name: 'Road trip' } }] }]]);
  assert.deepEqual(await client.createPlaylist(' Road trip '), { playlistId: 'p.new', title: 'Road trip' });
  assert.deepEqual(requests[0].body, { attributes: { name: 'Road trip' } });
  await client.createPlaylist('Road trip', catalogTrack);
  assert.deepEqual(requests[1].body.relationships, { tracks: { data: [{ id: '111', type: 'songs' }] } });
});

test('saving a catalog album adds it once and reports the library copy', async () => {
  const { client, requests } = appleMusic([
    [/^GET .*\/v1\/catalog\/us\/albums\/42\?relate=library$/, 200, { data: [{ id: '42' }] }],
    [/^POST .*\/v1\/me\/library\?ids%5Balbums%5D=42$/, 202, null]
  ]);
  assert.equal(await client.collectionSaved('am.albums.42'), false);
  assert.deepEqual(await client.setCollectionSaved('am.albums.42', true), { browseId: 'am.albums.42', saved: true });
  assert.equal(requests[0].headers['Music-User-Token'], 'user');
  assert.deepEqual(requests.map((request) => request.method), ['GET', 'GET', 'POST']);
});

test('removing deletes the library copy but never an own playlist', async () => {
  const saved = { data: [{ id: 'pl.x', relationships: { library: { data: [{ id: 'p.mine' }] } } }] };
  const { client, requests } = appleMusic([
    [/^GET .*\/v1\/catalog\/us\/playlists\/pl\.x\?relate=library$/, 200, saved],
    [/^GET .*\/v1\/me\/library\/playlists\/p\.mine$/, 200, { data: [{ attributes: { canEdit: false } }] }],
    [/^DELETE .*\/v1\/me\/library\/playlists\/p\.mine$/, 204, null],
    [/^GET .*\/v1\/me\/library\/playlists\/p\.own$/, 200, { data: [{ attributes: { canEdit: true } }] }]
  ]);
  assert.equal(await client.collectionSaved('am.playlists.pl.x'), true);
  assert.deepEqual(await client.setCollectionSaved('am.playlists.pl.x', false), { browseId: 'am.playlists.pl.x', saved: false });
  assert.ok(requests.some((request) => request.method === 'DELETE'));
  await assert.rejects(client.setCollectionSaved('am.playlists.p.own', false), /own playlists/);
});
