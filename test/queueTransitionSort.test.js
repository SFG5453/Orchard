import assert from 'node:assert/strict';
import test from 'node:test';
import { ref } from 'vue';

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
    ['active', { bpm: 100, beatConfidence: 0.82, key: 'C major', keyConfidence: 0.82 }],
    ['rough', { bpm: 145, beatConfidence: 0.82, key: 'F♯ major', keyConfidence: 0.82 }],
    ['smooth', { bpm: 102, beatConfidence: 0.82, key: 'G major', keyConfidence: 0.82 }],
    ['followup', { bpm: 105, beatConfidence: 0.82, key: 'D major', keyConfidence: 0.82 }]
  ]);
  let preloadCalls = 0;
  let lookupTracks = [];
  const ctx = {
    activeTrack: ref(activeTrack),
    queue: ref(queue),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({
      status: 'ready',
      trackId: 'active',
      bpm: 100,
      beatConfidence: 0.82,
      key: 'C major',
      keyConfidence: 0.82
    }),
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

  assert.deepEqual(lookupTracks.map((track) => track.id), ['rough', 'smooth', 'followup']);
  assert.equal(ctx.queue.value[0].id, 'smooth');
  assert.equal(ctx.transitionQueueSorted.value, true);
  assert.equal(preloadCalls, 1);
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
    { bpm: index === 0 ? 160 : 100 + index, beatConfidence: 0.82 }
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
          ['active', { bpm: 100, beatConfidence: 0.82 }],
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

  assert.equal(lookupTracks.length, 50);
  assert.deepEqual(ctx.queue.value.slice(50).map((track) => track.id), originalTail);
});
