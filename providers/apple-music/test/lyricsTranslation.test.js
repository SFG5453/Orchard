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
import { parseTranslations } from '../src/lyrics.js';

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

const localized = '<tt><head><metadata><iTunesMetadata><translations>'
  + '<translation type="subtitle" xml:lang="en-US"><text for="L1">Tom &amp; Jerry</text><text for="L2">Second <span>line</span></text></translation>'
  + '</translations><transliterations><transliteration xml:lang="ja-Latn"><text for="L1">romaji</text></transliteration></transliterations>'
  + '</iTunesMetadata></metadata></head></tt>';

test('translations read the subtitle block for the requested language only', () => {
  assert.deepEqual(parseTranslations(localized, 'en-US'), { L1: 'Tom & Jerry', L2: 'Second line' });
  assert.deepEqual(parseTranslations(localized, 'en'), { L1: 'Tom & Jerry', L2: 'Second line' });
  assert.deepEqual(parseTranslations(localized, 'es-MX'), {});
  assert.deepEqual(parseTranslations('<translations/>', 'en-US'), {});
});

test('lyrics translation asks the editorial API for the language with the listener token', async () => {
  const { client, requests } = appleMusic([
    [/\/v1\/catalog\/us\/songs\/111$/, 200, { data: [{ id: '111', type: 'songs', attributes: { name: 'Idol' } }] }],
    [/amp-api\.music\.apple\.com\/v1\/catalog\/us\/songs\/111\/syllable-lyrics/, 200, { data: [{ attributes: { ttmlLocalizations: localized } }] }]
  ]);
  const result = await client.lyricsTranslation({ appleId: '111', language: 'en-US' });
  assert.deepEqual(result, { songId: '111', language: 'en-US', translations: { L1: 'Tom & Jerry', L2: 'Second line' } });
  const request = requests.find((entry) => entry.url.includes('syllable-lyrics'));
  assert.ok(request.url.includes('extend=ttmlLocalizations'));
  assert.ok(request.url.includes('l%5Blyrics%5D=en-US'));
  assert.equal(request.headers['Media-User-Token'], 'user');
});

test('lyrics translation falls back to line timing and misses quietly', async () => {
  const { client } = appleMusic([
    [/\/v1\/catalog\/us\/songs\/111$/, 200, { data: [{ id: '111', type: 'songs', attributes: { name: 'Idol' } }] }],
    [/\/songs\/111\/lyrics\?/, 200, { data: [{ attributes: { ttmlLocalizations: localized } }] }],
    [/\/v1\/catalog\/us\/songs\/222$/, 200, { data: [{ id: '222', type: 'songs', attributes: { name: 'Quiet' } }] }],
    [/\/songs\/222\/(syllable-lyrics|lyrics)\?/, 200, { data: [{ attributes: { ttmlLocalizations: '<translations/>' } }] }]
  ]);
  assert.equal((await client.lyricsTranslation({ appleId: '111', language: 'en-US' })).songId, '111');
  assert.equal(await client.lyricsTranslation({ appleId: '222', language: 'en-US' }), null);
  assert.equal(await client.lyricsTranslation({ appleId: '111' }), null);
});
