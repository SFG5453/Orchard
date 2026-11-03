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

const webRoutes = [
  [/^https:\/\/music\.apple\.com\/us\/new$/, '<script type="module" src="/assets/index~abc.js"></script>'],
  [/^https:\/\/music\.apple\.com\/assets\/index~abc\.js$/, 'const qa="eyJ0eXAi.eyJpc3Mi.c2ln";']
];

function appleMusic(session = { userToken: 'user' }) {
  const requests = [];
  const client = createAppleMusic({
    fetchImpl: async (url, init = {}) => {
      requests.push({ url, headers: init.headers || {} });
      const web = webRoutes.find(([pattern]) => pattern.test(url));
      if (web) return { ok: true, status: 200, json: async () => ({}), text: async () => web[1] };
      const ids = new URL(url).searchParams.get('ids')?.split(',') || [];
      const data = ids.filter((id) => id !== '404').map((id) => ({ id, type: 'audio-analysis', attributes: { bpm: { main: Number(id) } } }));
      return { ok: true, status: 200, json: async () => ({ data }), text: async () => '' };
    },
    session: () => ({ developerToken: 'dev', storefront: 'gb', ...session })
  });
  client.requests = requests;
  return client;
}

test('audio analysis batches catalog ids on music.apple.com and keys results by id', async () => {
  const client = appleMusic();
  const result = await client.audioAnalysis(['am.songs.120', '95', 'am.songs.120', 'am.albums.7', 'yt-abc', '404']);
  assert.deepEqual(result, { items: { 120: { bpm: { main: 120 } }, 95: { bpm: { main: 95 } } } });
  const calls = client.requests.filter((request) => request.url.startsWith('https://amp-api.'));
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/v1\/catalog\/gb\/audio-analysis\?ids=120%2C95%2C404$/);
});

test('audio analysis splits long queues into batches of fifty', async () => {
  const client = appleMusic();
  const ids = Array.from({ length: 51 }, (_, index) => String(index + 1));
  const result = await client.audioAnalysis(ids);
  assert.equal(Object.keys(result.items).length, 51);
  assert.equal(client.requests.filter((request) => request.url.startsWith('https://amp-api.')).length, 2);
});

test('audio analysis needs a signed-in listener', async () => {
  const client = appleMusic({ userToken: '' });
  assert.deepEqual(await client.audioAnalysis(['1']), { items: {} });
  assert.equal(client.requests.length, 0);
});
