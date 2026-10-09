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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import { createRuntimeLibrary } from '../src/runtime/library.js';

const session = { cookie: 'SAPISID=test-cookie' };

function response(data) {
  return { ok: true, status: 200, text: async () => JSON.stringify(data) };
}

test('a playlist can be created without a seed song', async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    const endpoint = /\/youtubei\/v1\/([^?]+)/.exec(url)[1];
    calls.push({ endpoint, body: JSON.parse(init.body) });
    if (endpoint !== 'playlist/create') throw new Error(`Unexpected endpoint ${endpoint}`);
    return response({ playlistId: 'PLempty' });
  };
  const result = await createRuntimeLibrary(fetchImpl).createPlaylist({ session, title: ' Fresh start ' });
  assert.deepEqual(result, { playlistId: 'PLempty', videoId: '', title: 'Fresh start' });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].body.privacyStatus, 'PRIVATE');
  assert.deepEqual(calls[0].body.videoIds, []);
});

test('an empty playlist still needs a name', async () => {
  await assert.rejects(
    createRuntimeLibrary(async () => response({})).createPlaylist({ session, title: '   ' }),
    /Enter a playlist name/
  );
});
