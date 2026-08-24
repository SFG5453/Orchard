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
import { normalizeTrackAnalysis } from '../shared/trackAnalysis.js';
import {
  PAIR_TRANSITION_POLICY,
  planPairTransition
} from '../src/audio/crossfade/pairTransitionPlanner.js';

const ROOT = new Map([
  ['C', 0], ['C♯', 1], ['D', 2], ['E♭', 3], ['E', 4], ['F', 5],
  ['F♯', 6], ['G', 7], ['A♭', 8], ['A', 9], ['B♭', 10], ['B', 11]
]);

function chromaFor(key) {
  const [name, mode] = String(key).split(' ');
  const root = ROOT.get(name);
  if (!Number.isInteger(root)) return [];
  const third = mode === 'minor' ? 3 : 4;
  const values = Array(12).fill(0);
  values[root] = 1;
  values[(root + third) % 12] = 0.7;
  values[(root + 7) % 12] = 0.5;
  return values;
}

function track({
  role = 'outgoing',
  duration = 120,
  bpm = 120,
  beatConfidence = 0.92,
  key = 'C major',
  keyConfidence = 0.9,
  chroma = chromaFor(key),
  audibleStart = 0,
  contentEnd = duration,
  boundaryTimes = role === 'outgoing' ? [104, 112] : [16, 32],
  boundaryConfidence = 0.9,
  downbeats,
  vocal = () => 0.08,
  energy,
  low = () => 0.45,
  stability = () => 0.88,
  novelty = () => 0.55,
  meterConfidence = 0.85
} = {}) {
  const beatInterval = 60 / bpm;
  const beats = [];
  const generatedDownbeats = [];
  for (let time = 0, index = 0; time <= duration; time += beatInterval, index += 1) {
    beats.push(Number(time.toFixed(6)));
    if (index % 4 === 0) generatedDownbeats.push(Number(time.toFixed(6)));
  }
  const finalDownbeats = downbeats === undefined ? generatedDownbeats : downbeats;
  const defaultEnergy = role === 'outgoing'
    ? (time) => time < 96 ? 0.78 : Math.max(0.28, 0.78 - (time - 96) * 0.025)
    : (time) => time < 16 ? 0.3 + time * 0.025 : 0.72;
  const transitionFeatureFrames = [];
  for (let time = 0; time <= duration; time += 0.5) {
    transitionFeatureFrames.push({
      time,
      energy: (energy || defaultEnergy)(time),
      low: low(time),
      mid: 0.58,
      high: 0.42,
      ...(typeof vocal === 'function' ? { vocal: vocal(time) } : {}),
      novelty: novelty(time),
      transientDensity: 0.18,
      stability: stability(time)
    });
  }
  return normalizeTrackAnalysis({
    duration,
    bpm,
    beatInterval,
    beatConfidence,
    downbeatConfidence: beatConfidence,
    beats,
    downbeats: finalDownbeats,
    audibleStartTime: audibleStart,
    pickupConfidence: 0.9,
    contentEndTime: contentEnd,
    key,
    keyConfidence,
    chroma,
    transitionFeatureFrames,
    structuralBoundaryCandidates: boundaryTimes.map((time, index) => ({
      time,
      confidence: Array.isArray(boundaryConfidence)
        ? boundaryConfidence[index]
        : boundaryConfidence,
      source: 'detected-change',
      noveltyPeak: novelty(time),
      energyDelta: 0.6,
      lowDelta: 0.3,
      vocalDelta: 0.2,
      stabilityBefore: stability(Math.max(0, time - 2)),
      stabilityAfter: stability(Math.min(duration, time + 2)),
      downbeatDistance: 0
    })),
    meter: {
      beatsPerBar: 4,
      confidence: meterConfidence,
      source: meterConfidence >= 0.5 ? 'detected' : 'assumed-4-4'
    }
  });
}

function cleanPair(overrides = {}) {
  return {
    analysis: track({ role: 'outgoing', ...(overrides.outgoing || {}) }),
    nextAnalysis: track({ role: 'incoming', ...(overrides.incoming || {}) }),
    duration: overrides.outgoing?.duration || 120,
    nextDuration: overrides.incoming?.duration || 120
  };
}

function reasonCount(plan, reason) {
  return Number(plan.diagnostics?.reasonCounts?.[reason]) || 0;
}

test('chooses a full beatmatched transition for a clean compatible pair', () => {
  const plan = planPairTransition(cleanPair());

  assert.equal(plan.status, 'planned');
  assert.equal(plan.transitionClass, 'full_beatmatched');
  assert.equal(plan.renderMode, 'native');
  assert.ok(plan.confidence >= PAIR_TRANSITION_POLICY.confidence.full);
  assert.ok(plan.outgoing.end > plan.outgoing.start);
  assert.equal(plan.incoming.resume, plan.incoming.handoff);
  assert.ok(['beatmatched_crossfade', 'bass_swap'].includes(plan.strategy));
  assert.equal(plan.fallback.transitionClass, 'simple_crossfade');

  const selected = plan.diagnostics.selected;
  assert.equal(selected.outgoingEnd, plan.outgoing.end);
  assert.equal(selected.incomingHandoff, plan.incoming.handoff);
  assert.equal(selected.transitionClass, plan.transitionClass);
  assert.equal(selected.strategy, plan.strategy);
  assert.equal(selected.confidence, plan.confidence);
  assert.deepEqual(Object.keys(selected.components).sort(), [
    'beat',
    'dspRisk',
    'duration',
    'energy',
    'harmonic',
    'spectral',
    'stability',
    'structure',
    'tempo',
    'vocal'
  ]);
  assert.equal(plan.fallbackReason, '');
});

test('demotes close-tempo candidates with sustained lead-vocal collision', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { vocal: (time) => time >= 95 ? 0.92 : 0.05 },
    incoming: { vocal: (time) => time <= 36 ? 0.9 : 0.05 }
  }));

  assert.ok(!['full_beatmatched', 'conservative_beatmatched'].includes(plan.transitionClass));
  assert.ok(reasonCount(plan, 'vocal-collision') > 0);
  assert.notEqual(plan.renderMode, 'native');
});

test('vetoes elaborate overlap for a high-confidence severe harmonic clash', () => {
  const plan = planPairTransition(cleanPair({
    incoming: { key: 'F♯ major', keyConfidence: 0.95 }
  }));

  assert.ok(!['full_beatmatched', 'conservative_beatmatched'].includes(plan.transitionClass));
  assert.ok(reasonCount(plan, 'harmonic-clash') > 0);
});

test('falls back when tempo distance exceeds transparent stretching', () => {
  const plan = planPairTransition(cleanPair({ incoming: { bpm: 138 } }));

  assert.notEqual(plan.renderMode, 'native');
  assert.ok(reasonCount(plan, 'tempo-distance') > 0);
});

test('does not authorize beatmatching from a low-confidence grid', () => {
  const plan = planPairTransition(cleanPair({ outgoing: { beatConfidence: 0.3 } }));

  assert.notEqual(plan.renderMode, 'native');
  assert.ok(reasonCount(plan, 'beat-confidence') > 0);
});

test('uses a simple fallback when the incoming track has no usable intro runway', () => {
  const plan = planPairTransition(cleanPair({
    incoming: { boundaryTimes: [0], downbeats: [] }
  }));

  assert.ok(['simple_crossfade', 'silence_trim', 'normal_boundary'].includes(plan.transitionClass));
  assert.notEqual(plan.renderMode, 'native');
  assert.match(plan.fallbackReason, /incoming|candidate|runway/);
});

test('reports which tempo is missing when pair construction cannot begin', () => {
  const plan = planPairTransition(cleanPair({ outgoing: { bpm: 0 } }));

  assert.equal(plan.status, 'fallback');
  assert.equal(plan.fallbackReason, 'outgoing-tempo');
  assert.equal(plan.diagnostics.reasonCounts['outgoing-tempo'], 1);
});

test('avoids outgoing vocals at the end by choosing a cleaner earlier boundary', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: {
      contentEnd: 112,
      boundaryTimes: [104, 112],
      boundaryConfidence: [0.82, 0.96],
      vocal: (time) => time >= 106 ? 0.94 : 0.04
    }
  }));

  assert.equal(plan.outgoing.end, 104);
  assert.ok(plan.transitionClass === 'full_beatmatched' || plan.transitionClass === 'conservative_beatmatched');
});

test('selects a useful incoming cue well after timestamp zero', () => {
  const plan = planPairTransition(cleanPair({
    incoming: { boundaryTimes: [32], boundaryConfidence: 0.94 }
  }));

  assert.equal(plan.incoming.handoff, 32);
  assert.ok(plan.incoming.start > 0);
});

test('true pairwise scoring can reject the locally highest-ranked cue pair', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: {
      contentEnd: 112,
      boundaryTimes: [104, 112],
      boundaryConfidence: [0.82, 0.98],
      vocal: (time) => time >= 106 ? 0.95 : 0.05
    },
    incoming: {
      boundaryTimes: [16, 32],
      boundaryConfidence: [0.98, 0.82],
      vocal: (time) => time <= 20 ? 0.94 : 0.05
    }
  }));

  assert.equal(plan.outgoing.end, 104);
  assert.equal(plan.incoming.handoff, 32);
  assert.ok(plan.diagnostics.generated.detailed > 1);
});

test('rejects complex mixing when every candidate is poor', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: {
      beatConfidence: 0.25,
      boundaryConfidence: 0.2,
      key: 'C major',
      keyConfidence: 0.95,
      vocal: () => 0.9,
      stability: () => 0.2
    },
    incoming: {
      bpm: 148,
      beatConfidence: 0.25,
      boundaryConfidence: 0.2,
      key: 'F♯ major',
      keyConfidence: 0.95,
      vocal: () => 0.9,
      stability: () => 0.2
    }
  }));

  assert.ok(['simple_crossfade', 'silence_trim', 'normal_boundary'].includes(plan.transitionClass));
  assert.notEqual(plan.renderMode, 'native');
  assert.ok(plan.confidence < PAIR_TRANSITION_POLICY.confidence.conservative);
});

test('refusal classes keep executable boundary semantics', () => {
  const silenceTrim = planPairTransition(cleanPair({
    outgoing: {
      beatConfidence: 0,
      boundaryConfidence: 0,
      contentEnd: 116,
      key: '',
      keyConfidence: 0,
      chroma: [],
      vocal: () => 1,
      energy: () => 0,
      stability: () => 0,
      meterConfidence: 0
    },
    incoming: {
      beatConfidence: 0,
      boundaryConfidence: 0,
      key: '',
      keyConfidence: 0,
      chroma: [],
      vocal: () => 1,
      energy: () => 0,
      stability: () => 0,
      meterConfidence: 0
    }
  }));
  assert.equal(silenceTrim.transitionClass, 'silence_trim');
  assert.equal(silenceTrim.fallback.transitionClass, 'silence_trim');
  assert.equal(silenceTrim.fallback.outgoingStart, 116);
  assert.equal(silenceTrim.fallback.outgoingEnd, 116);
  assert.equal(silenceTrim.fallback.durationSeconds, 0);
  assert.equal(silenceTrim.fallback.transitionStyle, 'silence_trim');

  const normalBoundary = planPairTransition(cleanPair({
    outgoing: {
      beatConfidence: 0,
      boundaryConfidence: 0,
      bpm: 120,
      key: 'C major',
      keyConfidence: 1,
      vocal: () => 1,
      energy: () => 0,
      stability: () => 0,
      meterConfidence: 0
    },
    incoming: {
      beatConfidence: 0,
      boundaryConfidence: 0,
      bpm: 130,
      key: 'F♯ major',
      keyConfidence: 1,
      vocal: () => 1,
      energy: () => 0,
      stability: () => 0,
      meterConfidence: 0
    }
  }));
  assert.equal(normalBoundary.transitionClass, 'normal_boundary');
  assert.equal(normalBoundary.fallback.transitionClass, 'normal_boundary');
  assert.equal(normalBoundary.fallback.outgoingStart, 120);
  assert.equal(normalBoundary.fallback.outgoingEnd, 120);
  assert.equal(normalBoundary.fallback.durationSeconds, 0);
  assert.equal(normalBoundary.fallback.transitionStyle, 'normal_boundary');
});

test('uses explicitly low-confidence rhythmic fallbacks when structure is unavailable', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { boundaryTimes: [], meterConfidence: 0.15 },
    incoming: { boundaryTimes: [], meterConfidence: 0.15 }
  }));

  assert.notEqual(plan.transitionClass, 'full_beatmatched');
  assert.ok(plan.diagnostics.topCandidates.some((candidate) =>
    candidate.sources.includes('rhythmic-fallback') || candidate.sources.includes('downbeat-evidence')
  ));
});

test('conflicting low-confidence structural evidence cannot authorize a full mix', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { boundaryConfidence: 0.3, meterConfidence: 0.15 },
    incoming: { boundaryConfidence: 0.25, meterConfidence: 0.15 }
  }));

  assert.notEqual(plan.transitionClass, 'full_beatmatched');
  assert.ok(reasonCount(plan, 'structure-confidence') > 0);
});

test('keeps a brief vocal crossing eligible for a deliberate transition', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { vocal: (time) => time >= 111.5 && time <= 112 ? 0.9 : 0.05 },
    incoming: { vocal: (time) => time >= 31.5 && time <= 32 ? 0.9 : 0.05 }
  }));

  assert.ok(['full_beatmatched', 'conservative_beatmatched'].includes(plan.transitionClass));
  assert.equal(reasonCount(plan, 'vocal-collision'), 0);
});

test('unknown harmonic and vocal evidence lower confidence without false vetoes', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { key: '', keyConfidence: 0, chroma: [], vocal: null },
    incoming: { key: '', keyConfidence: 0, chroma: [], vocal: null }
  }));

  assert.equal(reasonCount(plan, 'harmonic-clash'), 0);
  assert.equal(reasonCount(plan, 'vocal-collision'), 0);
  assert.notEqual(plan.transitionClass, 'normal_boundary');
  assert.ok(plan.confidence < planPairTransition(cleanPair()).confidence);
});

test('keeps selection deterministic and diagnostics bounded', () => {
  const manyBoundaries = Array.from({ length: 30 }, (_, index) => index * 2 + 2);
  const input = cleanPair({
    outgoing: { boundaryTimes: manyBoundaries },
    incoming: { boundaryTimes: manyBoundaries }
  });
  const first = planPairTransition(input);
  const second = planPairTransition(input);

  assert.deepEqual(first, second);
  assert.ok(first.diagnostics.generated.outgoing <= 12);
  assert.ok(first.diagnostics.generated.incoming <= 12);
  assert.ok(first.diagnostics.generated.detailed <= 64);
  assert.ok(first.diagnostics.topCandidates.length <= 5);
});
