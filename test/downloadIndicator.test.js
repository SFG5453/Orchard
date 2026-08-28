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
import { ref } from 'vue';
import { installSongCacheActions } from '../src/app/playback/songCacheActions.js';

function createMockContext() {
  const ctx = {
    activeTrack: ref(null),
    queue: ref([]),
    songCacheEnabled: ref(true),
    songCacheMaxSizeMb: ref(512),
    songCacheInventory: ref({
      entries: [],
      downloads: [],
      cacheBytes: 0,
      downloadedBytes: 0
    }),
    songCacheLoading: ref(false),
    songCachePrefetching: ref(false),
    songCacheMessage: ref(''),
    downloadBusyTrackIds: ref([]),
    downloadPreparingCollectionId: ref(''),
    downloadMessage: ref(''),
    socket: ref({ connected: true }),
    isPlayableTrack(track) {
      return Boolean(track?.id || track?.videoId);
    },
    trackResolvePayload(track) {
      return { videoId: track.id || track.videoId };
    },
    trackWithCollectionContext(track, detail) {
      return {
        ...track,
        album: detail?.kind === 'album' ? detail.title : track.album,
        albumId: detail?.kind === 'album' ? detail.browseId : track.albumId
      };
    }
  };

  installSongCacheActions(ctx);
  return ctx;
}

test('isTrackDownloaded accurately reflects song cache downloaded inventory', () => {
  const ctx = createMockContext();
  const track1 = { id: 'song-1', title: 'Song One' };
  const track2 = { id: 'song-2', title: 'Song Two' };

  assert.equal(ctx.isTrackDownloaded(track1), false);
  assert.equal(ctx.isTrackDownloaded(track2), false);

  ctx.songCacheInventory.value = {
    ...ctx.songCacheInventory.value,
    downloads: [
      {
        videoId: 'song-1',
        title: 'Song One',
        artist: 'Artist One',
        downloaded: true
      }
    ]
  };

  assert.equal(ctx.isTrackDownloaded(track1), true);
  assert.equal(ctx.isTrackDownloaded(track2), false);
  assert.equal(ctx.isTrackDownloaded(null), false);
  assert.equal(ctx.isTrackDownloaded({}), false);
});

test('isTrackDownloading reflects downloadBusyTrackIds reactively', () => {
  const ctx = createMockContext();
  const track = { id: 'song-downloading', title: 'Downloading Song' };

  assert.equal(ctx.isTrackDownloading(track), false);

  ctx.downloadBusyTrackIds.value = ['song-downloading'];
  assert.equal(ctx.isTrackDownloading(track), true);

  ctx.downloadBusyTrackIds.value = [];
  assert.equal(ctx.isTrackDownloading(track), false);
});

test('download indicators work seamlessly across album and playlist collections', () => {
  const ctx = createMockContext();
  const albumTracks = [
    { id: 'album-track-1', title: 'Track 1', index: 1 },
    { id: 'album-track-2', title: 'Track 2', index: 2 }
  ];

  ctx.songCacheInventory.value = {
    ...ctx.songCacheInventory.value,
    downloads: [
      {
        videoId: 'album-track-1',
        title: 'Track 1',
        album: 'Test Album',
        albumId: 'MPREb_test_album',
        downloaded: true,
        collections: [{ kind: 'album', browseId: 'MPREb_test_album' }]
      }
    ]
  };

  assert.equal(ctx.isTrackDownloaded(albumTracks[0]), true);
  assert.equal(ctx.isTrackDownloaded(albumTracks[1]), false);

  ctx.downloadBusyTrackIds.value = ['album-track-2'];
  assert.equal(ctx.isTrackDownloading(albumTracks[0]), false);
  assert.equal(ctx.isTrackDownloading(albumTracks[1]), true);
});
