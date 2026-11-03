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
const album = (id, name) => ({ id, type: 'albums', attributes: { name, artistName: 'SZA', releaseDate: '2022-12-09', artwork: art } });
const video = {
  id: '1662016466',
  type: 'music-videos',
  attributes: {
    name: 'Kill Bill',
    artistName: 'SZA',
    durationInMillis: 263019,
    contentRating: 'explicit',
    isrc: 'USRC12203614',
    playParams: { id: '1662016466', kind: 'musicVideo' },
    artwork: { url: 'https://is1.mzstatic.com/video/{w}x{h}mv.jpg', width: 1920, height: 1080 }
  }
};

function music(routes, calls) {
  const fetchImpl = async (url) => {
    calls.push(url);
    const route = routes.find(([pattern]) => pattern.test(url));
    if (!route) return { ok: false, status: 404, json: async () => ({}) };
    return { ok: true, status: 200, json: async () => route[1] };
  };
  return createAppleMusic({ fetchImpl, session: () => ({ developerToken: 'dev', storefront: 'us' }) });
}

test('album pages carry their shelves, music videos first', async () => {
  const calls = [];
  const views = {
    'other-versions': { attributes: { title: 'Other Versions' }, data: [album('1657869377', 'SOS'), album('deluxe', 'SOS Deluxe')] },
    'related-videos': { attributes: { title: 'Music Videos' }, data: [video] }
  };
  const routes = [
    [/albums\/1657869377\?views=/, { data: [{ ...album('1657869377', 'SOS'), views }] }],
    [/albums\/1657869377\?/, { data: [{ ...album('1657869377', 'SOS'), relationships: { tracks: { data: [] } } }] }]
  ];
  const page = await music(routes, calls).albumPage('am.albums.1657869377');
  assert.ok(calls.some((url) => /views=related-videos%2Cother-versions/.test(url)));
  assert.deepEqual(page.sections.map((section) => section.key), ['related-videos', 'other-versions']);
  // The album itself is not one of its other versions.
  assert.deepEqual(page.sections[1].items.map((item) => item.title), ['SOS Deluxe']);
  const [clip] = page.sections[0].items;
  assert.equal(clip.id, 'am.music-videos.1662016466');
  assert.equal(clip.type, 'video');
  assert.equal(clip.thumbnail, 'https://is1.mzstatic.com/video/640x360mv.jpg');
  assert.equal(clip.duration, '4:23');
  assert.equal(clip.unplayable, false);
});

test('album shelves are optional', async () => {
  const routes = [[/albums\/1657869377\?include/, { data: [{ ...album('1657869377', 'SOS'), relationships: { tracks: { data: [] } } }] }]];
  const page = await music(routes, []).albumPage('am.albums.1657869377');
  assert.deepEqual(page.sections, []);
});
