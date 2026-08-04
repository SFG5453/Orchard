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

import test from 'node:test';
import assert from 'node:assert/strict';
import { createYouTubeLikesService } from '../electron/catalog/youtubeLikes.js';

function serviceFor(info) {
  return createYouTubeLikesService({
    refreshBrowserAuth: async () => {},
    ensureSignedIn: async () => ({
      music: { getInfo: async () => info },
      actions: { execute: async () => {} }
    })
  });
}

test('liked status reads the YouTube Music player overlay action', async () => {
  const service = serviceFor({
    basic_info: { is_liked: undefined },
    player_overlays: { actions: [{ like_status: 'LIKE' }] }
  });

  assert.deepEqual(await service.status({ videoId: 'video-1' }), {
    videoId: 'video-1',
    liked: true
  });
});

test('liked status preserves an indifferent YouTube Music rating', async () => {
  const service = serviceFor({
    basic_info: { is_liked: undefined },
    player_overlays: { actions: [{ like_status: 'INDIFFERENT' }] }
  });

  assert.equal((await service.status({ videoId: 'video-1' })).liked, false);
});

test('liked status falls back to parsed response memo actions', async () => {
  const service = serviceFor({
    basic_info: { is_liked: undefined },
    page: [null, {
      contents_memo: new Map([['MusicLikeButton', [{ like_status: 'LIKE' }]]])
    }]
  });

  assert.equal((await service.status({ videoId: 'video-1' })).liked, true);
});
