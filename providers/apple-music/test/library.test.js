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

const art = { url: 'https://is1.mzstatic.com/image/{w}x{h}{c}.{f}', width: 3000, height: 3000 };

const webRoutes = [
  [/^https:\/\/music\.apple\.com\/us\/new$/, 200, '<script type="module" src="/assets/index~abc.js"></script>'],
  [/^https:\/\/music\.apple\.com\/assets\/index~abc\.js$/, 200, 'const qa="eyJ0eXAi.eyJpc3Mi.c2ln";']
];

function appleMusic(routes) {
  const requests = [];
  const client = createAppleMusic({
    fetchImpl: async (url, init = {}) => {
      requests.push({ url, headers: init.headers || {} });
      const route = [...webRoutes, ...routes].find(([pattern]) => pattern.test(url));
      if (!route) return { ok: false, status: 404, json: async () => ({}), text: async () => '' };
      const body = route[2];
      return { ok: route[1] < 400, status: route[1], json: async () => body, text: async () => String(body) };
    },
    session: () => ({ developerToken: 'dev', userToken: 'user', storefront: 'us' })
  });
  return { client, requests };
}

const librarySong = (id, catalogId, name) => ({
  id,
  type: 'library-songs',
  attributes: {
    name, artistName: 'SZA', albumName: 'SOS', durationInMillis: 180000, artwork: art,
    playParams: { id, kind: 'song', isLibrary: true, ...(catalogId ? { catalogId } : {}) }
  }
});

test('library sections prefer catalog ids and keep page size across pages', async () => {
  const { client, requests } = appleMusic([
    [/\/v1\/me\/library\/songs\?limit=100$/, 200, { data: [librarySong('i.a', '111', 'Kill Bill')], next: '/v1/me/library/songs?offset=100' }],
    [/\/v1\/me\/library\/songs\?offset=100&limit=100$/, 200, { data: [librarySong('i.b', '', 'Demo')] }],
    [/\/v1\/me\/library\/albums/, 200, { data: [{ id: 'l.x', type: 'library-albums', attributes: { name: 'SOS', artistName: 'SZA', artwork: art, playParams: { id: 'l.x', catalogId: '1657869377' } } }] }],
    [/\/v1\/me\/library\/artists\?limit=100&include=catalog$/, 200, { data: [
      { id: 'r.1', type: 'library-artists', attributes: { name: 'SZA' }, relationships: { catalog: { data: [{ id: '605800394', type: 'artists', attributes: { name: 'SZA', artwork: art } }] } } },
      { id: 'r.2', type: 'library-artists', attributes: { name: 'Uploads' } }
    ] }],
    [/\/v1\/me\/library\/playlists/, 503, {}]
  ]);
  const { sections } = await client.library();
  const byKey = Object.fromEntries(sections.map((section) => [section.key, section]));
  assert.deepEqual(byKey.songs.items.map((song) => [song.id, song.appleId]), [['am.songs.111', '111'], ['am.songs.i.b', '']]);
  assert.equal(byKey.albums.items[0].browseId, 'am.albums.1657869377');
  assert.deepEqual(byKey.artists.items.map((artist) => artist.browseId), ['am.artists.605800394']);
  assert.equal(byKey.playlists.items.length, 0);
  assert.match(byKey.playlists.error, /503/);
  assert.ok(requests.every((request) => request.headers['Music-User-Token'] === 'user'));
});

test('library playlists open from the library with their tracks', async () => {
  const { client } = appleMusic([
    [/\/v1\/me\/library\/playlists\/p\.abc\/tracks/, 200, { data: [librarySong('i.a', '111', 'Kill Bill')] }],
    [/\/v1\/me\/library\/playlists\/p\.abc$/, 200, { data: [{ id: 'p.abc', type: 'library-playlists', attributes: { name: 'Mine', canEdit: true, playParams: { id: 'p.abc' } } }] }]
  ]);
  const page = await client.playlistPage('am.playlists.p.abc');
  assert.equal(page.browseId, 'am.playlists.p.abc');
  assert.equal(page.title, 'Mine');
  assert.equal(page.author, 'You');
  assert.equal(page.thumbnail, 'https://is1.mzstatic.com/image/600x600bb.jpg');
  assert.deepEqual(page.tracks.map((song) => song.id), ['am.songs.111']);
});

test('an empty library playlist is a page with no tracks', async () => {
  const { client } = appleMusic([
    [/\/v1\/me\/library\/playlists\/p\.new\/tracks/, 404, {}],
    [/\/v1\/me\/library\/playlists\/p\.new$/, 200, { data: [{ id: 'p.new', type: 'library-playlists', attributes: { name: 'New', canEdit: true } }] }]
  ]);
  const page = await client.playlistPage('am.playlists.p.new');
  assert.equal(page.tracks.length, 0);
});

test('lyrics match the song and read syllable TTML with the listener token', async () => {
  const { client, requests } = appleMusic([
    [/\/v1\/catalog\/us\/search\?term=Kill%20Bill%20SZA/, 200, { results: { songs: { data: [
      { id: '999', type: 'songs', attributes: { name: 'Kill Bill (Remix)', artistName: 'Someone' } },
      { id: '111', type: 'songs', attributes: { name: 'Kill Bill', artistName: 'SZA', albumName: 'SOS' } }
    ] } } }],
    [/amp-api\.music\.apple\.com\/v1\/catalog\/us\/songs\/111\/syllable-lyrics/, 200, { data: [{ attributes: { ttml: '<tt/>' } }] }]
  ]);
  const result = await client.lyrics({ title: 'Kill Bill (Official Video)', artist: 'SZA - Topic' });
  assert.deepEqual(result, { songId: '111', ttml: '<tt/>' });
  const lyricsRequest = requests.find((request) => request.url.includes('syllable-lyrics'));
  assert.equal(lyricsRequest.headers['Media-User-Token'], 'user');
  assert.equal(lyricsRequest.headers.Authorization, 'Bearer eyJ0eXAi.eyJpc3Mi.c2ln');
});

test('lyrics fall back to line timing and miss quietly', async () => {
  const { client } = appleMusic([
    [/\/v1\/catalog\/us\/songs\/111$/, 200, { data: [{ id: '111', type: 'songs', attributes: { name: 'Kill Bill' } }] }],
    [/\/songs\/111\/lyrics$/, 200, { data: [{ attributes: { ttml: '<tt>lines</tt>' } }] }],
    [/\/v1\/catalog\/us\/songs\/222$/, 200, { data: [{ id: '222', type: 'songs', attributes: { name: 'Quiet' } }] }]
  ]);
  assert.equal((await client.lyrics({ appleId: '111' })).ttml, '<tt>lines</tt>');
  assert.equal(await client.lyrics({ appleId: '222' }), null);
  assert.equal(await client.lyrics({ appleId: 'i.local' }), null);
});

test('recent tracks keep catalog and library songs in order and skip other kinds', async () => {
  const catalogSong = { id: '222', type: 'songs', attributes: { name: 'Snooze', artistName: 'SZA', albumName: 'SOS', durationInMillis: 201000, artwork: art } };
  const { client, requests } = appleMusic([
    [/\/v1\/me\/recent\/played\/tracks\?limit=30$/, 200, {
      data: [catalogSong, { id: 'ra.1', type: 'stations', attributes: { name: 'Radio' } }, librarySong('i.a', '111', 'Kill Bill')]
    }]
  ]);
  const tracks = await client.recentTracks();
  assert.deepEqual(tracks.map((item) => [item.browseId || item.id, item.title]), [['am.songs.222', 'Snooze'], ['am.songs.111', 'Kill Bill']]);
  assert.match(requests.at(-1).url, /recent\/played\/tracks\?limit=30$/);
});
