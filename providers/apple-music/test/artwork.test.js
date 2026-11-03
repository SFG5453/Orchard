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

const art = {
  url: 'https://is1.mzstatic.com/image/{w}x{h}{c}.{f}', width: 3000, height: 3000,
  bgColor: '024c5d', textColor1: 'ead1c3', textColor2: 'd0d0d6'
};
const tall = {
  url: 'https://is1.mzstatic.com/hero/{w}x{h}bb.jpg', width: 1680, height: 2240,
  bgColor: '153739', textColor1: 'ffffff', textColor2: 'eeeeee'
};

const sos = {
  data: [{
    id: '1657869377',
    type: 'albums',
    attributes: {
      name: 'SOS',
      artistName: 'SZA',
      artwork: art,
      editorialArtwork: { superHeroTall: tall },
      editorialVideo: {
        motionDetailSquare: { video: 'https://mvod.example/square.m3u8' },
        motionDetailTall: { video: 'https://mvod.example/tall.m3u8' }
      }
    }
  }]
};

const searchSong = (id, name, artistName, albumName, albumId) => ({
  id,
  type: 'songs',
  attributes: { name, artistName, albumName, url: `https://music.apple.com/us/album/x/${albumId}?i=${id}` }
});

// music.apple.com's page and bundle, the source of the editorial token.
const webRoutes = [
  [/^https:\/\/music\.apple\.com\/us\/new$/, 200, '<script type="module" src="/assets/index~abc.js"></script>'],
  [/^https:\/\/music\.apple\.com\/assets\/index~abc\.js$/, 200, 'const qa="eyJ0eXAi.eyJpc3Mi.c2ln";']
];

function appleMusic(routes, calls = [], session = { userToken: 'user' }) {
  const requests = [];
  const client = createAppleMusic({
    fetchImpl: async (url, init = {}) => {
      requests.push({ url, headers: init.headers || {} });
      if (!/^https:\/\/music\.apple\.com\//.test(url)) calls.push(url);
      const route = [...webRoutes, ...routes].find(([pattern]) => pattern.test(url));
      if (!route) return { ok: false, status: 404, json: async () => ({}), text: async () => '' };
      const body = route[2];
      return { ok: route[1] < 400, status: route[1], json: async () => body, text: async () => String(body) };
    },
    session: () => ({ developerToken: 'dev', storefront: 'us', ...session })
  });
  client.requests = requests;
  return client;
}

const expected = {
  albumId: 'am.albums.1657869377',
  name: 'SOS',
  artist: 'SZA',
  static: 'https://is1.mzstatic.com/image/1200x1200bb.jpg',
  animated: 'https://mvod.example/square.m3u8',
  animatedVertical: 'https://mvod.example/tall.m3u8',
  heroTall: 'https://is1.mzstatic.com/hero/960x1280bb.jpg',
  colors: { background: '#153739', text: '#ffffff', secondaryText: '#eeeeee' }
};

const plain = { data: [{ ...sos.data[0], attributes: { name: 'SOS', artistName: 'SZA', artwork: art } }] };

test('an Apple album id reads motion artwork straight from the album', async () => {
  const calls = [];
  const result = await appleMusic([[/\/albums\/1657869377\?/, 200, sos]], calls).artwork({ albumId: 'am.albums.1657869377' });
  assert.deepEqual(result, expected);
  assert.match(calls[0], /^https:\/\/amp-api\.music\.apple\.com\/.*extend=editorialArtwork%2CeditorialVideo/);
});

test('editorial reads carry the web token and music.apple.com origin, never the user token', async () => {
  const client = appleMusic([[/\/albums\/1657869377\?/, 200, sos]]);
  await client.artwork({ albumId: 'am.albums.1657869377' });
  const amp = client.requests.find((request) => request.url.startsWith('https://amp-api.'));
  assert.equal(amp.headers.Authorization, 'Bearer eyJ0eXAi.eyJpc3Mi.c2ln');
  assert.equal(amp.headers.Origin, 'https://music.apple.com');
  assert.equal(amp.headers['Music-User-Token'], undefined);
});

test('the web token is fetched once per session', async () => {
  const client = appleMusic([[/\/albums\/1657869377\?/, 200, sos]]);
  await client.artwork({ albumId: 'am.albums.1657869377' });
  await client.artwork({ albumId: 'am.albums.1657869377' });
  assert.equal(client.requests.filter((request) => request.url.includes('/assets/')).length, 1);
});

test('a YouTube song is matched by title, artist and album before its album is read', async () => {
  const results = {
    results: {
      songs: {
        data: [
          searchSong('1', 'Kill Bill', 'SZA', 'Kill Bill - Single', '111'),
          searchSong('2', 'Kill Bill', 'SZA', 'SOS', '1657869377'),
          searchSong('3', 'Kill Bill', 'Someone Else', 'SOS', '999')
        ]
      }
    }
  };
  const calls = [];
  const result = await appleMusic([[/\/search\?/, 200, results], [/\/albums\/1657869377\?/, 200, sos]], calls)
    .artwork({ title: 'Kill Bill (Official Audio)', artist: 'SZA', album: 'SOS' });
  assert.deepEqual(result, expected);
  assert.match(calls[0], /types=songs/);
});

test('an Apple song resolves its album through the song relationship', async () => {
  const songResponse = { data: [{ id: '2', type: 'songs', attributes: {}, relationships: { albums: { data: [{ id: '1657869377' }] } } }] };
  const result = await appleMusic([[/\/songs\/2(\?|$)/, 200, songResponse], [/\/albums\/1657869377\?/, 200, sos]])
    .artwork({ appleId: '2' });
  assert.equal(result.animated, expected.animated);
});

test('album pages search albums by name', async () => {
  const albums = { results: { albums: { data: [{ id: '1657869377', type: 'albums', attributes: { name: 'SOS (Deluxe)', artistName: 'SZA' } }] } } };
  const result = await appleMusic([[/types=albums/, 200, albums], [/\/albums\/1657869377\?/, 200, sos]])
    .artwork({ kind: 'album', title: 'SOS', artist: 'SZA' });
  assert.equal(result.animatedVertical, expected.animatedVertical);
});

test('no matching song means no artwork', async () => {
  const results = { results: { songs: { data: [searchSong('3', 'Other Song', 'SZA', 'SOS', '1657869377')] } } };
  const result = await appleMusic([[/\/search\?/, 200, results]]).artwork({ title: 'Kill Bill', artist: 'SZA' });
  assert.equal(result, null);
});

test('signed out, artwork never touches music.apple.com', async () => {
  const client = appleMusic([[/^https:\/\/api\.music\.apple\.com\/.*\/albums\/1657869377/, 200, plain]], [], {});
  const result = await client.artwork({ albumId: 'am.albums.1657869377' });
  assert.equal(result.static, expected.static);
  assert.equal(result.animated, '');
  assert.equal(result.heroTall, '');
  assert.deepEqual(result.colors, { background: '#024c5d', text: '#ead1c3', secondaryText: '#d0d0d6' });
  assert.ok(client.requests.every((request) => request.url.startsWith('https://api.music.apple.com')));
});

test('a refused editorial read falls back to the public API and drops the web token', async () => {
  const calls = [];
  const client = appleMusic([
    [/^https:\/\/amp-api\./, 401, {}],
    [/^https:\/\/api\.music\.apple\.com\/.*\/albums\/1657869377/, 200, plain]
  ], calls);
  const result = await client.artwork({ albumId: 'am.albums.1657869377' });
  assert.equal(result.static, expected.static);
  assert.equal(result.animated, '');
  assert.match(calls[1], /^https:\/\/api\.music\.apple\.com/);
  await client.artwork({ albumId: 'am.albums.1657869377' });
  assert.equal(client.requests.filter((request) => request.url.includes('/assets/')).length, 2);
});
