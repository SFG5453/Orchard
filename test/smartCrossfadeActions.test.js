import assert from 'node:assert/strict';
import test from 'node:test';

import { installSmartCrossfadeActions } from '../src/app/playback/smartCrossfadeActions.js';

function localAnalysis() {
  return {
    analysisVersion: 7,
    duration: 180,
    bpm: 123,
    analyzedBpm: 123,
    beatInterval: 60 / 123,
    beatConfidence: 0.8,
    bpmSource: 'local-native',
    beats: [0, 60 / 123],
    downbeats: [0],
    phraseBoundaries: [0, 16],
    mixInTime: 16,
    mixOutTime: 172
  };
}

function context({ analyze, lookup }) {
  const logs = [];
  return {
    audioAnalyzer: { decodeAudio: async () => null },
    createSmartCrossfadeAnalyzer: () => ({
      analyze,
      destroy() {},
      report: (event, details) => logs.push({ event, details })
    }),
    createBpmMetadataClient: () => ({ lookup }),
    logs
  };
}

test('GetSongBPM 404 does not block successful local analysis', async () => {
  const ctx = context({
    analyze: async () => localAnalysis(),
    lookup: async () => null
  });
  installSmartCrossfadeActions(ctx);

  await ctx.analyzeCurrentCrossfadeTrack({ id: 'uncatalogued', title: 'Song' }, 'stream', 180);
  assert.equal(ctx.crossfadeAnalysis.value.status, 'ready');
  assert.equal(ctx.crossfadeAnalysis.value.bpm, 123);
  assert.equal(ctx.crossfadeAnalysis.value.bpmSource, 'local-native');
  assert.equal(ctx.crossfadeAnalysis.value.mixOutTime, 172);
});

test('GetSongBPM timeout remains optional and does not delay local readiness', async () => {
  const neverSettles = new Promise(() => {});
  const ctx = context({
    analyze: async () => localAnalysis(),
    lookup: () => neverSettles
  });
  installSmartCrossfadeActions(ctx);

  await ctx.analyzeNextCrossfadeTrack({ id: 'timeout', title: 'Song' }, 'stream', 180);
  assert.equal(ctx.nextCrossfadeAnalysis.value.status, 'ready');
  assert.equal(ctx.nextCrossfadeAnalysis.value.bpm, 123);
  assert.equal(ctx.nextCrossfadeAnalysis.value.bpmSource, 'local-native');
});

test('both local and catalog failures produce a clean unavailable state without action-level retries', async () => {
  let attempts = 0;
  const ctx = context({
    analyze: async () => {
      attempts += 1;
      throw new Error('decode failed');
    },
    lookup: async () => null
  });
  installSmartCrossfadeActions(ctx);

  await ctx.analyzeCurrentCrossfadeTrack({ id: 'failed', title: 'Song' }, 'stream', 180);
  assert.equal(attempts, 1);
  assert.equal(ctx.crossfadeAnalysis.value.status, 'unavailable');
  assert.equal(ctx.crossfadeAnalysis.value.bpm, 0);
  assert.ok(ctx.logs.some((entry) => entry.event === 'track-unavailable'));
});

test('late weak catalog enrichment preserves the strong local BPM and timing grid', async () => {
  let resolveMetadata;
  const metadata = new Promise((resolve) => { resolveMetadata = resolve; });
  const ctx = context({
    analyze: async () => localAnalysis(),
    lookup: () => metadata
  });
  installSmartCrossfadeActions(ctx);

  await ctx.analyzeCurrentCrossfadeTrack({ id: 'enriched', title: 'Song' }, 'stream', 180);
  resolveMetadata({
    bpm: 97,
    tempoConfidence: 0.2,
    matchConfidence: 0.3,
    source: 'GetSongBPM'
  });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(ctx.crossfadeAnalysis.value.bpm, 123);
  assert.equal(ctx.crossfadeAnalysis.value.catalogBpm, 97);
  assert.equal(ctx.crossfadeAnalysis.value.bpmSource, 'local-native');
  assert.deepEqual(ctx.crossfadeAnalysis.value.beats, localAnalysis().beats);
  assert.equal(ctx.crossfadeAnalysis.value.mixOutTime, 172);
});
