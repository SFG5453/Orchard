import assert from 'node:assert/strict';
import test from 'node:test';
import { nextTick, ref } from 'vue';

import {
  bestTransitionOrder,
  installQueueTransitionSort,
  transitionCost
} from '../src/app/playback/queueTransitionSort.js';

function ids(tracks) {
  return tracks.map((track) => track.id);
}

test('transition scoring ignores artist and album identity', () => {
  const left = {
    bpm: 120,
    beatConfidence: 0.8,
    key: 'C major',
    keyConfidence: 0.8
  };
  const right = {
    bpm: 124,
    beatConfidence: 0.8,
    key: 'G major',
    keyConfidence: 0.8
  };
  const baseline = transitionCost(left, right);
  const identityTagged = transitionCost(
    { ...left, album: 'same album', artist: 'same artist' },
    { ...right, album: 'same album', artist: 'same artist' }
  );
  assert.equal(identityTagged, baseline);
});

test('queues without musical evidence remain untouched', () => {
  const queue = [
    { id: 'one', artist: 'Repeated Artist' },
    { id: 'two', artist: 'Repeated Artist' },
    { id: 'three', artist: 'Someone Else' }
  ];
  const result = bestTransitionOrder(queue, new Map(), {});
  assert.equal(result.comparisons, 0);
  assert.deepEqual(ids(result.ordered), ['one', 'two', 'three']);
});

test('BPM and harmonic compatibility determine the route', () => {
  const queue = [{ id: 'rough' }, { id: 'smooth' }, { id: 'followup' }];
  const analyses = new Map([
    ['rough', { bpm: 145, beatConfidence: 0.9, key: 'F♯ major', keyConfidence: 0.8 }],
    ['smooth', { bpm: 102, beatConfidence: 0.9, key: 'G major', keyConfidence: 0.8 }],
    ['followup', { bpm: 105, beatConfidence: 0.9, key: 'D major', keyConfidence: 0.8 }]
  ]);
  const result = bestTransitionOrder(queue, analyses, {
    bpm: 100,
    beatConfidence: 0.9,
    key: 'C major',
    keyConfidence: 0.8
  });
  assert.equal(result.ordered[0].id, 'smooth');
  assert.notDeepEqual(ids(result.ordered), ids(queue));
});

test('unanalyzed tracks keep their positions and split sortable segments', () => {
  const queue = [{ id: 'known-a' }, { id: 'unknown' }, { id: 'known-b' }, { id: 'known-c' }];
  const analyses = new Map([
    ['known-a', { bpm: 130, key: 'A major' }],
    ['known-b', { bpm: 90, key: 'E major' }],
    ['known-c', { bpm: 92, key: 'B major' }]
  ]);
  const result = bestTransitionOrder(queue, analyses, { bpm: 128, key: 'D major' });
  assert.equal(result.ordered[1].id, 'unknown');
  assert.deepEqual(ids(result.ordered), ['known-a', 'unknown', 'known-b', 'known-c']);
});

test('Best mix loads BPM service metadata before sorting an unanalyzed queue', async () => {
  const activeTrack = { id: 'active', title: 'Active', artist: 'Artist' };
  const queue = [
    { id: 'rough', title: 'Rough', artist: 'Artist' },
    { id: 'smooth', title: 'Smooth', artist: 'Artist' },
    { id: 'followup', title: 'Followup', artist: 'Artist' }
  ];
  const bpm = new Map([
    ['active', { bpm: 100, tempoConfidence: 0.82, key: 'C major', keyConfidence: 0.82 }],
    ['rough', { bpm: 145, tempoConfidence: 0.82, key: 'F♯ major', keyConfidence: 0.82 }],
    ['smooth', { bpm: 102, tempoConfidence: 0.82, key: 'G major', keyConfidence: 0.82 }],
    ['followup', { bpm: 105, tempoConfidence: 0.82, key: 'D major', keyConfidence: 0.82 }]
  ]);
  let preloadCalls = 0;
  let lookupTracks = [];
  const ctx = {
    activeTrack: ref(activeTrack),
    queue: ref(queue),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    bpmMetadata: {
      lookupMany: async (tracks) => {
        lookupTracks = tracks;
        return bpm;
      }
    },
    clearNextPreload() {},
    preloadNextTrack: async () => { preloadCalls += 1; },
    showShareMessage() {}
  };

  installQueueTransitionSort(ctx);
  await ctx.toggleTransitionQueueSort();

  assert.deepEqual(lookupTracks.map((track) => track.id), ['active', 'rough', 'smooth', 'followup']);
  assert.equal(ctx.queue.value[0].id, 'smooth');
  assert.equal(ctx.transitionQueueSorted.value, true);
  assert.equal(preloadCalls, 1);
});

test('Best mix locally analyzes cache misses through the authenticated resolver with queue priorities', async () => {
  const activeTrack = {
    id: 'active',
    title: 'Active',
    artist: 'Artist',
    streamUrl: 'http://127.0.0.1/stream/active'
  };
  const queue = [
    { id: 'rough', title: 'Rough', artist: 'Artist', durationSeconds: 180 },
    { id: 'smooth', title: 'Smooth', artist: 'Artist', durationSeconds: 181 },
    { id: 'followup', title: 'Followup', artist: 'Artist', durationSeconds: 182 }
  ];
  const local = new Map([
    ['active', { bpm: 100, bpmSource: 'local-native', beatConfidence: 0.9, key: 'C major' }],
    ['rough', { bpm: 145, bpmSource: 'local-worker', beatConfidence: 0.9, key: 'F♯ major' }],
    ['smooth', { bpm: 102, bpmSource: 'local-worker', beatConfidence: 0.9, key: 'G major' }],
    ['followup', { bpm: 105, bpmSource: 'local-worker', beatConfidence: 0.9, key: 'D major' }]
  ]);
  const requests = [];
  const resolved = [];
  const ctx = {
    activeTrack: ref(activeTrack),
    queue: ref(queue),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    smartCrossfadeAnalyzer: {
      async analyze(trackId, streamSource, options) {
        requests.push({ trackId, priority: options.priority, streamSourceType: typeof streamSource });
        if (typeof streamSource === 'function') await streamSource();
        return local.get(trackId);
      },
      report() {}
    },
    resolvePlayableTrack: async (track, options) => {
      resolved.push({ trackId: track.id, options });
      return { streamUrl: `http://127.0.0.1/stream/${track.id}` };
    },
    bpmMetadata: {
      lookupMany: async () => new Map([
        ['active', { bpm: 170, source: 'GetSongBPM' }],
        ['rough', { bpm: 90, source: 'GetSongBPM' }],
        ['smooth', { bpm: 150, source: 'GetSongBPM' }],
        ['followup', { bpm: 80, source: 'GetSongBPM' }]
      ])
    },
    clearNextPreload() {},
    preloadNextTrack: async () => {},
    showShareMessage() {}
  };

  installQueueTransitionSort(ctx);
  await ctx.toggleTransitionQueueSort();

  assert.deepEqual(requests.map(({ trackId, priority }) => [trackId, priority]), [
    ['active', 0],
    ['rough', 1],
    ['smooth', 2],
    ['followup', 2]
  ]);
  assert.equal(requests[0].streamSourceType, 'string');
  assert.deepEqual(resolved.map((entry) => entry.trackId), ['rough', 'smooth', 'followup']);
  assert.ok(resolved.every((entry) => entry.options.preload && entry.options.mediaKind === 'audio'));
  assert.equal(ctx.queue.value[0].id, 'smooth');
});

test('Best mix does not wait for optional catalog metadata when local analysis is sufficient', async () => {
  const queue = [
    { id: 'rough', title: 'Rough', artist: 'Artist' },
    { id: 'smooth', title: 'Smooth', artist: 'Artist' }
  ];
  const ctx = {
    activeTrack: ref({ id: 'active', streamUrl: 'local-active' }),
    queue: ref(queue),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    smartCrossfadeAnalyzer: {
      async analyze(trackId) {
        return trackId === 'active'
          ? { bpm: 100, bpmSource: 'local-native' }
          : trackId === 'smooth'
            ? { bpm: 102, bpmSource: 'local-worker' }
            : { bpm: 145, bpmSource: 'local-worker' };
      },
      report() {}
    },
    resolvePlayableTrack: async (track) => ({ streamUrl: `local-${track.id}` }),
    bpmMetadata: { lookupMany: () => new Promise(() => {}) },
    clearNextPreload() {},
    preloadNextTrack: async () => {},
    showShareMessage() {}
  };

  installQueueTransitionSort(ctx);
  await Promise.race([
    ctx.toggleTransitionQueueSort(),
    new Promise((_, reject) => setTimeout(() => reject(new Error('Best mix waited for catalog metadata')), 100))
  ]);

  assert.equal(ctx.queue.value[0].id, 'smooth');
});

test('Best mix stays enabled when the existing order is already optimal', async () => {
  const queue = [
    { id: 'first', title: 'First', artist: 'Artist' },
    { id: 'second', title: 'Second', artist: 'Artist' }
  ];
  const ctx = {
    activeTrack: ref({ id: 'active' }),
    queue: ref(queue),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    bpmMetadata: {
      lookupMany: async () => new Map([
        ['active', { bpm: 100 }],
        ['first', { bpm: 101 }],
        ['second', { bpm: 102 }]
      ])
    },
    clearNextPreload() {},
    preloadNextTrack: async () => {},
    showShareMessage() {}
  };

  installQueueTransitionSort(ctx);
  await ctx.toggleTransitionQueueSort();

  assert.equal(ctx.transitionQueueSorted.value, true);
  assert.deepEqual(ctx.transitionQueueExpectedIds, ['first', 'second']);
});

test('Best mix survives song consumption and reprocesses appended refill tracks', async () => {
  const analyses = new Map([
    ['active', { bpm: 100, bpmSource: 'local-native' }],
    ['first', { bpm: 101, bpmSource: 'local-worker' }],
    ['second', { bpm: 102, bpmSource: 'local-worker' }],
    ['refill', { bpm: 103, bpmSource: 'local-worker' }]
  ]);
  const requests = [];
  const ctx = {
    activeTrack: ref({ id: 'active', streamUrl: 'local-active' }),
    queue: ref([
      { id: 'first', title: 'First' },
      { id: 'second', title: 'Second' }
    ]),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    smartCrossfadeAnalyzer: {
      async analyze(trackId) {
        requests.push(trackId);
        return analyses.get(trackId);
      },
      report() {}
    },
    resolvePlayableTrack: async (track) => ({ streamUrl: `local-${track.id}` }),
    bpmMetadata: { lookupMany: async () => new Map() },
    clearNextPreload() {},
    preloadNextTrack: async () => {},
    showShareMessage() {}
  };

  installQueueTransitionSort(ctx);
  await ctx.toggleTransitionQueueSort();
  assert.equal(ctx.transitionQueueSorted.value, true);

  ctx.queue.value = [ctx.queue.value[1]];
  await nextTick();
  assert.equal(ctx.transitionQueueSorted.value, true);

  ctx.queue.value = [...ctx.queue.value, { id: 'refill', title: 'Refill' }];
  await nextTick();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(ctx.transitionQueueSorted.value, true);
  assert.ok(requests.includes('refill'));
  assert.deepEqual(new Set(ctx.transitionQueueExpectedIds), new Set(['second', 'refill']));
});

test('Best mix only looks up and reorders the next 50 queued songs', async () => {
  const queue = Array.from({ length: 55 }, (_, index) => ({
    id: `track-${index}`,
    title: `Track ${index}`,
    artist: 'Artist'
  }));
  const originalTail = queue.slice(50).map((track) => track.id);
  const bpm = new Map(queue.slice(0, 50).map((track, index) => [
    track.id,
    { bpm: index === 0 ? 160 : 100 + index, tempoConfidence: 0.82 }
  ]));
  let lookupTracks = [];
  const ctx = {
    activeTrack: ref({ id: 'active', title: 'Active', artist: 'Artist' }),
    queue: ref(queue),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    bpmMetadata: {
      lookupMany: async (tracks) => {
        lookupTracks = tracks;
        return new Map([
          ['active', { bpm: 100, tempoConfidence: 0.82 }],
          ...bpm
        ]);
      }
    },
    clearNextPreload() {},
    preloadNextTrack: async () => {},
    showShareMessage() {}
  };

  installQueueTransitionSort(ctx);
  await ctx.toggleTransitionQueueSort();

  assert.equal(lookupTracks.length, 51);
  assert.equal(lookupTracks[0].id, 'active');
  assert.deepEqual(
    lookupTracks.slice(1).map((track) => track.id),
    queue.slice(0, 50).map((track) => track.id)
  );
  assert.deepEqual(ctx.queue.value.slice(50).map((track) => track.id), originalTail);
});
