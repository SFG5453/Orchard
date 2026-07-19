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
