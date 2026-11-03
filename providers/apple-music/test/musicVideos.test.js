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

function appleMusic(routes) {
  const requests = [];
  const client = createAppleMusic({
    fetchImpl: async (url) => {
      requests.push(url);
      const route = routes.find(([pattern]) => pattern.test(url));
      if (!route) return { ok: false, status: 404, json: async () => ({}), text: async () => '' };
      return { ok: route[1] < 400, status: route[1], json: async () => route[2], text: async () => JSON.stringify(route[2]) };
    },
    session: () => ({ developerToken: 'dev', userToken: 'user', storefront: 'us' })
  });
  return { client, requests };
}

test('musicVideoFor returns the song\'s Apple music video', async () => {
  const { client, requests } = appleMusic([
    [/songs\/1450695739\/music-videos/, 200, { data: [{ id: '1458003118', type: 'music-videos' }] }]
  ]);
  assert.deepEqual(await client.musicVideoFor('am.songs.1450695739'), { videoId: '1458003118' });
  assert.match(requests[0], /\/v1\/catalog\/us\/songs\/1450695739\/music-videos/);
});

test('musicVideoFor is empty without a video or a catalog id', async () => {
  const { client, requests } = appleMusic([[/songs\/1\/music-videos/, 200, { data: [] }]]);
  assert.deepEqual(await client.musicVideoFor('am.songs.1'), {});
  assert.deepEqual(await client.musicVideoFor('am.songs.2'), {});
  assert.deepEqual(await client.musicVideoFor('i.abc'), {});
  assert.deepEqual(await client.musicVideoFor('am.albums.3'), {});
  assert.equal(requests.length, 2);
});
