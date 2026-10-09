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

import { createPlaylistMutations } from '../src/catalog/playlistMutations.js';

test('playlist creation accepts a batch without a transport bridge', async () => {
  let createdWith;
  const mutations = createPlaylistMutations({
    ensureSignedIn: async () => ({
      playlist: {
        create: async (title, ids) => {
          createdWith = { title, ids };
          return { success: true, playlist_id: 'PL-created' };
        }
      }
    }),
    refreshBrowserAuth: async () => {}
  });

  assert.deepEqual(await mutations.create({
    title: 'Roadtrip',
    videoIds: ['vid-1', 'vid-2']
  }), { id: 'PL-created', title: 'Roadtrip' });
  assert.deepEqual(createdWith, { title: 'Roadtrip', ids: ['vid-1', 'vid-2'] });
});

test('playlist additions use injected YouTube.js client access directly', async () => {
  let addedWith;
  const mutations = createPlaylistMutations({
    ensureSignedIn: async () => ({
      music: { getPlaylist: async () => ({ header: { edit_header: {} } }) },
      playlist: {
        addVideos: async (id, ids) => {
          addedWith = { id, ids };
          return { success: true };
        }
      }
    }),
    refreshBrowserAuth: async () => {}
  });

  assert.deepEqual(await mutations.add({
    playlistId: 'PL-existing',
    videoIds: ['vid-10', 'vid-20']
  }), { id: 'PL-existing' });
  assert.deepEqual(addedWith, { id: 'PL-existing', ids: ['vid-10', 'vid-20'] });
});

