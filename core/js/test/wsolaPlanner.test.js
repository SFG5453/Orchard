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
import { planTransition } from '../src/audio/crossfade/transitionPlanner.js';
import {
  alignTempoOctave,
  planWsolaTransition
} from '../src/audio/crossfade/wsolaPlanner.js';

function track(role, {
  duration = 120,
  bpm = 120,
  beatConfidence = 0.92,
  key = 'C major',
  keyConfidence = 0.9,
  vocal = () => 0.08
} = {}) {
  const beatInterval = bpm > 0 ? 60 / bpm : 0;
  const beats = [];
  const downbeats = [];
  if (beatInterval > 0) {
    for (let time = 0, index = 0; time <= duration; time += beatInterval, index += 1) {
      beats.push(time);
      if (index % 4 === 0) downbeats.push(time);
    }
  }
  const boundary = role === 'outgoing'
    ? downbeats.at(-5) ?? duration
    : downbeats[8] ?? 16;
  const transitionFeatureFrames = [];
  for (let time = 0; time <= duration; time += 0.5) {
    transitionFeatureFrames.push({
      time,
      energy: role === 'outgoing'
        ? (time < duration - 24 ? 0.78 : Math.max(0.28, 0.78 - (time - duration + 24) * 0.025))
        : (time < boundary ? 0.3 + time * 0.025 : 0.72),
      low: 0.45,
      mid: 0.58,
      high: 0.42,
      vocal: vocal(time),
      novelty: 0.55,
      transientDensity: 0.18,
      stability: 0.88
    });
  }
  return normalizeTrackAnalysis({
    duration,
    bpm,
    beatInterval,
    beatConfidence,
    downbeatConfidence: beatConfidence,
    beats,
    downbeats,
    audibleStartTime: 0,
    pickupConfidence: 0.9,
    contentEndTime: duration,
    key,
    keyConfidence,
    chroma: key ? [1, 0, 0, 0, 0.7, 0, 0, 0.5, 0, 0, 0, 0] : [],
    transitionFeatureFrames,
    structuralBoundaryCandidates: [{
      time: boundary,
      confidence: 0.92,
      noveltyPeak: 0.8,
      energyDelta: 0.6,
      stabilityBefore: 0.88,
      stabilityAfter: 0.88,
      downbeatDistance: 0
    }],
    meter: { beatsPerBar: 4, confidence: 0.85, source: 'detected' }
  });
}

function pair(overrides = {}) {
  return {
    analysis: track('outgoing', overrides.outgoing),
    nextAnalysis: track('incoming', overrides.incoming),
    duration: overrides.outgoing?.duration || 120,
    nextDuration: overrides.incoming?.duration || 120
  };
}

test('live and WSOLA adapters expose the same authoritative pair decision', () => {
  const options = pair();
  const native = planWsolaTransition(options);
  const live = planTransition({
    ...options,
    currentTime: 0,
    currentTrack: { id: 'outgoing', durationSeconds: 120 },
    nextTrack: { id: 'incoming', durationSeconds: 120 },
    mode: 'smart'
  });

  assert.equal(native.ok, true, native.reason);
  assert.deepEqual(native.pairPlan, live.pairPlan);
  assert.equal(native.transitionStart, native.pairPlan.outgoing.start);
  assert.equal(native.transitionEnd, native.pairPlan.outgoing.end);
  assert.equal(native.incomingResumeTime, native.pairPlan.incoming.resume);
});

test('maps every exact selected-plan field without choosing new cues', () => {
  const plan = planWsolaTransition(pair());

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.transitionStart, plan.pairPlan.outgoing.start);
  assert.equal(plan.transitionEnd, plan.pairPlan.outgoing.end);
  assert.equal(plan.overlapSeconds, plan.pairPlan.durationSeconds);
  assert.equal(plan.beats, plan.pairPlan.beats);
  assert.equal(plan.targetBpm, plan.pairPlan.targetBpm);
  assert.equal(plan.outgoingTempoRatio, plan.pairPlan.outgoing.tempoRatio);
  assert.equal(plan.incomingTempoRatio, plan.pairPlan.incoming.tempoRatio);
  assert.equal(plan.incomingCueTime, plan.pairPlan.incoming.start);
  assert.equal(plan.incomingDropTime, plan.pairPlan.incoming.handoff);
  assert.equal(plan.incomingResumeTime, plan.pairPlan.incoming.resume);
  assert.equal(plan.strategy, plan.pairPlan.strategy);
});

test('preserves full-track grids and maps exact media times into padded slices', () => {
  const options = pair();
  const plan = planWsolaTransition(options);

  assert.deepEqual(plan.outgoingGrid.beats, options.analysis.timing.beats);
  assert.deepEqual(plan.outgoingGrid.downbeats, options.analysis.timing.downbeats);
  assert.deepEqual(plan.incomingGrid.beats, options.nextAnalysis.timing.beats);
  assert.deepEqual(plan.incomingGrid.downbeats, options.nextAnalysis.timing.downbeats);
  assert.equal(plan.outgoingSlice.start + plan.outgoingSlice.anchor, plan.transitionStart);
  assert.equal(plan.incomingSlice.start + plan.incomingSlice.anchor, plan.incomingCueTime);
  assert.ok(plan.outgoingSlice.end >= plan.transitionEnd);
  assert.ok(plan.incomingSlice.end >= plan.incomingResumeTime);
});

test('legacy semantic cue labels cannot change either adapter decision', () => {
  const options = pair();
  const plain = planWsolaTransition(options);
  const decorated = planWsolaTransition({
    ...options,
    analysis: {
      ...options.analysis,
      phraseBoundaries: [2, 4, 6],
      mixOutTime: 4,
      mixOutCandidates: [{ time: 4, type: 'outro_start', score: 1 }]
    },
    nextAnalysis: {
      ...options.nextAnalysis,
      phraseBoundaries: [80, 96],
      mixInTime: 96,
      mixInCandidates: [{ time: 96, type: 'main_drop', score: 1 }]
    }
  });

  assert.ok(plain.pairPlan);
  assert.deepEqual(decorated.pairPlan, plain.pairPlan);
  assert.equal(decorated.transitionStart, plain.transitionStart);
  assert.equal(decorated.incomingDropTime, plain.incomingDropTime);
});

test('WSOLA refusal carries the authoritative plan fallback without replanning', () => {
  const plan = planWsolaTransition(pair({ incoming: { bpm: 138 } }));

  assert.equal(plan.ok, false);
  assert.deepEqual(plan.fallback, plan.pairPlan.fallback);
  assert.equal(plan.reason, plan.pairPlan.fallbackReason);
  assert.equal(plan.pairPlan.diagnostics.reasonCounts['tempo-distance'] > 0, true);
});

test('low-confidence timing cannot authorize a native renderer', () => {
  const plan = planWsolaTransition(pair({ outgoing: { beatConfidence: 0.3 } }));

  assert.equal(plan.ok, false);
  assert.equal(plan.pairPlan.diagnostics.reasonCounts['beat-confidence'] > 0, true);
  assert.deepEqual(plan.fallback, plan.pairPlan.fallback);
});

test('sustained mapped vocal risk is forwarded as a conservative filtered mix', () => {
  const plan = planWsolaTransition(pair({
    outgoing: { vocal: (time) => time >= 70 ? 0.92 : 0.05 },
    incoming: { vocal: (time) => time <= 60 ? 0.9 : 0.05 }
  }));

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.pairPlan.transitionClass, 'conservative_beatmatched');
  assert.equal(plan.strategy, 'filtered_blend');
  assert.equal(plan.vocalClash, true);
  assert.equal(plan.pairPlan.diagnostics.reasonCounts['vocal-collision'] > 0, true);
  assert.deepEqual(plan.fallback, plan.pairPlan.fallback);
});

test('missing tempo produces a stable precheck reason and fallback', () => {
  const plan = planWsolaTransition(pair({ outgoing: { bpm: 0 } }));

  assert.equal(plan.ok, false);
  assert.equal(plan.reason, 'outgoing-tempo');
  assert.deepEqual(plan.fallback, plan.pairPlan.fallback);
});

test('octave tempo alignment remains available for compatibility callers', () => {
  assert.equal(alignTempoOctave(126, 63), 126);
  assert.equal(alignTempoOctave(126, 252), 126);
  assert.equal(alignTempoOctave(100, 98), 98);
});

test('adapter planning is deterministic', () => {
  const options = pair({ incoming: { bpm: 119 } });

  assert.deepEqual(planWsolaTransition(options), planWsolaTransition(options));
});
