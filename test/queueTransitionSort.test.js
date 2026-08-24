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
import { nextTick, ref } from 'vue';

import { AUDIO_ANALYSIS_VERSION } from '../shared/audioAnalysis.js';
import { finalizeTrackAnalysis } from '../shared/trackAnalysis.js';
import {
  bestTransitionOrder,
  installQueueTransitionSort,
  transitionCost
} from '../src/app/playback/queueTransitionSort.js';
import { supabaseClient } from '../src/services/supabaseClient.js';

function ids(tracks) {
  return tracks.map((track) => track.id);
}

function canonicalCloudAnalysis(bpm, key = 'C major') {
  const duration = 20;
  const beatInterval = 60 / bpm;
  const beats = [];
  const downbeats = [];
  for (let time = 0, index = 0; time <= duration; time += beatInterval, index += 1) {
    beats.push(time);
    if (index % 4 === 0) downbeats.push(time);
  }
  return finalizeTrackAnalysis({
    analysisVersion: AUDIO_ANALYSIS_VERSION,
    duration,
    bpm,
    beatInterval,
    beatConfidence: 0.9,
    downbeatConfidence: 0.9,
    beats,
    downbeats,
    audibleStartTime: 0,
    contentEndTime: duration,
    key,
    keyConfidence: 0.8,
    transitionFeatureFrames: [
      { time: 0, energy: 0.4, vocal: 0.1 },
      { time: duration, energy: 0.4, vocal: 0.1 }
    ],
    structuralBoundaryCandidates: [],
    meter: { beatsPerBar: 4, confidence: 0.15, source: 'assumed-4-4' }
  });
}

function plannerAnalysis({
  role,
  bpm = 120,
  key = 'C major',
  vocalProbability = 0.05,
  vocalAt = () => vocalProbability
}) {
  const duration = 120;
  const beatInterval = 60 / bpm;
  const beats = [];
  const downbeats = [];
  for (let time = 0, index = 0; time <= duration; time += beatInterval, index += 1) {
    beats.push(Number(time.toFixed(6)));
    if (index % 4 === 0) downbeats.push(Number(time.toFixed(6)));
  }
  const transitionFeatureFrames = [];
  for (let time = 0; time <= duration; time += 0.5) {
    const energy = role === 'outgoing'
      ? time < 96 ? 0.78 : Math.max(0.28, 0.78 - (time - 96) * 0.025)
      : time < 16 ? 0.3 + time * 0.025 : 0.72;
    transitionFeatureFrames.push({
      time,
      energy,
      low: 0.45,
      mid: 0.58,
      high: 0.42,
      vocal: vocalAt(time),
      novelty: 0.55,
      transientDensity: 0.18,
      stability: 0.88
    });
  }
  const boundaryTimes = role === 'outgoing' ? [104, 112] : [16, 32];
  return finalizeTrackAnalysis({
    analysisVersion: AUDIO_ANALYSIS_VERSION,
    duration,
    bpm,
    beatInterval,
    beatConfidence: 0.92,
    downbeatConfidence: 0.92,
    beats,
    downbeats,
    audibleStartTime: 0,
    pickupTime: 0,
    contentEndTime: duration,
    key,
    keyConfidence: 0.9,
    vocalProbability,
    transitionFeatureFrames,
    structuralBoundaryCandidates: boundaryTimes.map((time) => ({
      time,
      confidence: 0.9,
      source: 'detected-change',
      noveltyPeak: 0.55,
      energyDelta: 0.6,
      lowDelta: 0.3,
      vocalDelta: 0.2,
      stabilityBefore: 0.88,
      stabilityAfter: 0.88,
      downbeatDistance: 0
    })),
    meter: { beatsPerBar: 4, confidence: 0.85, source: 'detected' }
  });
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

test('Smart Crossfade safety outranks a BPM and key perfect vocal collision', () => {
  const active = plannerAnalysis({
    role: 'outgoing',
    vocalProbability: 0.92,
    vocalAt: (time) => time >= 70 ? 0.92 : 0.05
  });
  const unsafe = plannerAnalysis({
    role: 'incoming',
    vocalProbability: 0.9,
    vocalAt: (time) => time <= 60 ? 0.9 : 0.05
  });
  const safe = plannerAnalysis({
    role: 'incoming',
    bpm: 122,
    key: 'G major'
  });
  const result = bestTransitionOrder(
    [{ id: 'unsafe' }, { id: 'safe' }],
    new Map([['unsafe', unsafe], ['safe', safe]]),
    active
  );

  assert.equal(result.ordered[0].id, 'safe');
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

test('Best Mix bounds expensive pair planning at the 50-track limit', () => {
  const queue = Array.from({ length: 50 }, (_, index) => ({ id: `track-${index}` }));
  const analyses = new Map(queue.map((track, index) => [
    track.id,
    { bpm: 118 + (index % 9), beatConfidence: 0.8, key: 'C major', keyConfidence: 0.8 }
  ]));
  const result = bestTransitionOrder(queue, analyses, {
    bpm: 120,
    beatConfidence: 0.8,
    key: 'C major',
    keyConfidence: 0.8
  });

  assert.ok(Number.isInteger(result.plannerEvaluations));
  assert.ok(result.plannerEvaluations <= 150, `planned ${result.plannerEvaluations} pairs`);
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
        requests.push({
          trackId,
          priority: options.priority,
          forPlayback: options.forPlayback,
          streamSourceType: typeof streamSource
        });
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
  assert.ok(requests.every(({ forPlayback }) => forPlayback !== true));
  assert.deepEqual(resolved.map((entry) => entry.trackId), ['rough', 'smooth', 'followup']);
  assert.ok(resolved.every((entry) => entry.options.preload && entry.options.mediaKind === 'audio'));
  assert.equal(ctx.queue.value[0].id, 'smooth');
});

test('Best Mix carries queue analysis evidence into Smart Crossfade scoring', async (t) => {
  const originalFetch = supabaseClient.fetchTrackAnalysis;
  t.after(() => { supabaseClient.fetchTrackAnalysis = originalFetch; });
  supabaseClient.fetchTrackAnalysis = async () => [];

  const local = new Map([
    ['active', plannerAnalysis({
      role: 'outgoing',
      vocalProbability: 0.92,
      vocalAt: (time) => time >= 70 ? 0.92 : 0.05
    })],
    ['unsafe', plannerAnalysis({
      role: 'incoming',
      vocalProbability: 0.9,
      vocalAt: (time) => time <= 60 ? 0.9 : 0.05
    })],
    ['safe', plannerAnalysis({ role: 'incoming', bpm: 122, key: 'G major' })]
  ]);
  const ctx = {
    activeTrack: ref({ id: 'active', streamUrl: 'local-active', durationSeconds: 120 }),
    queue: ref([
      { id: 'unsafe', durationSeconds: 120 },
      { id: 'safe', durationSeconds: 120 }
    ]),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    smartCrossfadeAnalyzer: {
      async analyze(trackId) { return local.get(trackId); },
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

  assert.equal(ctx.queue.value[0].id, 'safe');
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

test('Best mix absorbs a rejected local write of valid cloud analysis', async (t) => {
  const previousBridge = globalThis.orchardAudioAnalysis;
  const originalFetch = supabaseClient.fetchTrackAnalysis;
  t.after(() => {
    supabaseClient.fetchTrackAnalysis = originalFetch;
    if (previousBridge === undefined) delete globalThis.orchardAudioAnalysis;
    else globalThis.orchardAudioAnalysis = previousBridge;
  });

  const analyses = new Map([
    ['active', canonicalCloudAnalysis(100, 'C major')],
    ['rough', canonicalCloudAnalysis(145, 'F♯ major')],
    ['smooth', canonicalCloudAnalysis(102, 'G major')]
  ]);
  supabaseClient.fetchTrackAnalysis = async (trackIds) => trackIds.map((id) => {
    const value = analyses.get(id);
    return {
      video_id: id,
      duration: value.duration,
      bpm: value.bpm,
      musical_key: value.key,
      key_confidence: value.keyConfidence,
      beat_confidence: value.beatConfidence,
      analysis_version: value.analysisVersion,
      analysis_data: value
    };
  });
  globalThis.orchardAudioAnalysis = {
    get: async () => null,
    store: async () => { throw new Error('cache write refused'); }
  };

  const ctx = {
    activeTrack: ref({ id: 'active' }),
    queue: ref([{ id: 'rough' }, { id: 'smooth' }]),
    shuffleEnabled: ref(false),
    shuffleSourceQueue: ref([]),
    crossfadeAnalysis: ref({}),
    crossfadeAnalysisByTrack: new Map(),
    bpmMetadata: { lookupMany: async () => new Map() },
    clearNextPreload() {},
    preloadNextTrack: async () => {},
    showShareMessage() {}
  };

  installQueueTransitionSort(ctx);
  await ctx.toggleTransitionQueueSort();
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(ctx.transitionQueueSorted.value, true);
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

test('Best Mix ranks pairs by executable choreography quality and confidence', () => {
  const outgoing = plannerAnalysis({ role: 'outgoing', bpm: 120, key: 'C major' });
  const beatmatched = plannerAnalysis({ role: 'incoming', bpm: 120, key: 'C major' });
  const distant = plannerAnalysis({ role: 'incoming', bpm: 155, key: 'F# major' });

  const costBeatmatched = transitionCost(outgoing, beatmatched);
  const costDistant = transitionCost(outgoing, distant);

  assert.ok(costBeatmatched < costDistant, 'Beatmatched staged blend must have lower cost than distant clash');

  const queue = [
    { id: 'distant', title: 'Distant' },
    { id: 'beatmatched', title: 'Beatmatched' }
  ];
  const analysisByTrack = new Map([
    ['distant', distant],
    ['beatmatched', beatmatched]
  ]);

  const result = bestTransitionOrder(queue, analysisByTrack, outgoing);
  assert.deepEqual(result.ordered.map((t) => t.id), ['beatmatched', 'distant']);
});

