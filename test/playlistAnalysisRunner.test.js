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

import { createPlaylistAnalysisRunner } from '../src/app/playback/playlistAnalysisRunner.js';

test('Best Mix analyzes downloaded tracks with four bounded workers', async () => {
  const tracks = Array.from({ length: 8 }, (_, index) => ({
    id: `track-${index}`,
    title: `Track ${index}`,
    durationSeconds: 180
  }));
  const analyzedUrls = [];
  const syncedIds = [];
  const storedIds = [];
  let active = 0;
  let maximumActive = 0;

  const ctx = {
    resolvePlayableTrack: async (track) => ({
      streamUrl: `http://127.0.0.1:9863/download/${track.id}`
    }),
    smartCrossfadeAnalyzer: {
      analyze: async (trackId, streamUrl, options) => {
        assert.equal(options.priority, 2);
        assert.ok(options.signal instanceof AbortSignal);
        analyzedUrls.push(streamUrl);
        active += 1;
        maximumActive = Math.max(maximumActive, active);
        await new Promise((resolve) => setTimeout(resolve, 15));
        active -= 1;
        return { bpm: 120, trackId };
      }
    }
  };
  const runner = createPlaylistAnalysisRunner(ctx, {
    fetchCloudAnalysis: async () => new Map([['track-0', { bpm: 118 }]]),
    syncAnalysis: (trackId) => { syncedIds.push(trackId); },
    storeAnalysis: (trackId) => { storedIds.push(trackId); }
  });

  const result = await runner.analyzePlaylist(tracks);

  assert.deepEqual(result, { success: 7, failed: 0, cached: 1 });
  assert.equal(maximumActive, 4);
  assert.equal(analyzedUrls.length, 7);
  assert.ok(analyzedUrls.every((url) => url.includes('/download/')));
  assert.equal(syncedIds.length, 7);
  assert.deepEqual(storedIds, ['track-0']);
  assert.equal(runner.completedTracks.value, 8);
  assert.equal(runner.progress.value, 1);
  assert.match(runner.currentStatus.value, /^Done\./);
});

test('cancelling Best Mix aborts active local analysis jobs', async () => {
  let analysisStarted;
  const started = new Promise((resolve) => { analysisStarted = resolve; });
  let aborted = false;
  const ctx = {
    resolvePlayableTrack: async (track) => ({ streamUrl: `/download/${track.id}` }),
    smartCrossfadeAnalyzer: {
      analyze: async (_trackId, _streamUrl, { signal }) => {
        analysisStarted();
        await new Promise((resolve, reject) => {
          signal.addEventListener('abort', () => {
            aborted = true;
            reject(new DOMException('Cancelled', 'AbortError'));
          }, { once: true });
        });
      }
    }
  };
  const runner = createPlaylistAnalysisRunner(ctx, {
    fetchCloudAnalysis: async () => new Map()
  });

  const pending = runner.analyzePlaylist([{ id: 'one' }, { id: 'two' }]);
  await started;
  runner.cancel();
  const result = await pending;

  assert.equal(aborted, true);
  assert.deepEqual(result, { success: 0, failed: 0, cached: 0 });
  assert.equal(runner.isAnalyzing.value, false);
  assert.equal(runner.progress.value, 0);
  assert.equal(runner.currentStatus.value, '');
});
