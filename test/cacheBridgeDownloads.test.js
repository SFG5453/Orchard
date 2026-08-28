/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import { downloadTracks } from '../electron/bridge/cacheBridge.js';

test('desktop downloads use the same three-worker concurrency bound as mobile', async () => {
  const tracks = Array.from({ length: 8 }, (_, index) => ({
    videoId: `track-${index}`,
    originalVideoId: `track-${index}`,
    title: `Track ${index}`,
    downloadCollections: [{ browseId: 'playlist-id', kind: 'playlist', title: 'Playlist' }]
  }));
  const resolvePayloads = [];
  const pinned = [];
  let active = 0;
  let maximumActive = 0;

  const result = await downloadTracks(
    tracks,
    async (payload) => {
      resolvePayloads.push(payload);
      return {
        youtubeVideoId: payload.videoId,
        streamUrl: `download://${payload.videoId}`
      };
    },
    {
      findDownloaded: async () => null,
      pin: async (sourceVideoId, metadata) => { pinned.push({ sourceVideoId, metadata }); }
    },
    async () => {
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await new Promise((resolve) => setTimeout(resolve, 15));
      active -= 1;
    }
  );

  assert.equal(maximumActive, 3);
  assert.equal(result.downloaded, tracks.length);
  assert.equal(result.failed, 0);
  assert.equal(pinned.length, tracks.length);
  assert.ok(resolvePayloads.every((payload) => payload.downloadRequested && payload.refreshStream));
  assert.ok(pinned.every(({ metadata }) => metadata.collections[0].browseId === 'playlist-id'));
});

test('adding an already-downloaded song to a playlist updates metadata without downloading again', async () => {
  let resolveCalls = 0;
  let drainCalls = 0;
  const pins = [];
  const result = await downloadTracks(
    [{
      videoId: 'library-id',
      title: 'Saved Song',
      downloadCollections: [{ browseId: 'playlist-id', kind: 'playlist', title: 'Playlist' }]
    }],
    async () => {
      resolveCalls += 1;
      return {};
    },
    {
      findDownloaded: async () => ({ videoId: 'library-id', sourceVideoId: 'source-id' }),
      pin: async (sourceVideoId, metadata) => { pins.push({ sourceVideoId, metadata }); }
    },
    async () => { drainCalls += 1; }
  );

  assert.equal(result.downloaded, 1);
  assert.equal(resolveCalls, 0);
  assert.equal(drainCalls, 0);
  assert.equal(pins[0].sourceVideoId, 'source-id');
  assert.equal(pins[0].metadata.collections[0].browseId, 'playlist-id');
});
