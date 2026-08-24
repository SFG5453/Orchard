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
  planTransition,
  transitionFromPairFallback
} from '../src/audio/crossfade/transitionPlanner.js';

function track(role, {
  duration = 120,
  bpm = 120,
  beatConfidence = 0.92,
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
    key: 'C major',
    keyConfidence: 0.9,
    chroma: [1, 0, 0, 0, 0.7, 0, 0, 0.5, 0, 0, 0, 0],
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

function smartOptions(overrides = {}) {
  return {
    analysis: overrides.analysis || track('outgoing'),
    currentTime: overrides.currentTime ?? 0,
    currentTrack: overrides.currentTrack || { id: 'outgoing', durationSeconds: 120 },
    duration: overrides.duration ?? 120,
    mode: 'smart',
    nextAnalysis: overrides.nextAnalysis || track('incoming'),
    nextTrack: overrides.nextTrack || { id: 'incoming', durationSeconds: 120 },
    ...overrides
  };
}

test('standard mode retains the configured equal-power crossfade', () => {
  const plan = planTransition({
    currentTime: 115,
    currentTrack: { durationSeconds: 120 },
    duration: 120,
    fadeSeconds: 6,
    mode: 'standard'
  });

  assert.equal(plan.transitionStart, 114);
  assert.equal(plan.transitionEnd, 120);
  assert.equal(plan.transitionStyle, 'equal_power');
  assert.equal(plan.shouldStart, true);
  assert.equal('pairPlan' in plan, false);
});

test('smart live playback executes the authoritative attached fallback', () => {
  const plan = planTransition(smartOptions());

  assert.equal(plan.pairPlan.renderMode, 'native');
  assert.deepEqual(plan.fallback, plan.pairPlan.fallback);
  assert.equal(plan.transitionStart, plan.fallback.outgoingStart);
  assert.equal(plan.transitionEnd, plan.fallback.outgoingEnd);
  assert.equal(plan.incomingCueTime, plan.fallback.incomingCue);
  assert.equal(plan.transitionStyle, 'equal_power');
  assert.equal(plan.shouldStart, false);
});

test('playback time only determines whether the fixed fallback should start', () => {
  const early = planTransition(smartOptions({ currentTime: 0 }));
  const due = planTransition(smartOptions({ currentTime: 119 }));

  assert.deepEqual(due.pairPlan, early.pairPlan);
  assert.equal(early.shouldStart, false);
  assert.equal(due.shouldStart, true);
});

test('a missed cue moves the same fallback to the final usable boundary', () => {
  const early = planTransition(smartOptions({ currentTime: 0 }));
  const late = planTransition(smartOptions({ currentTime: early.transitionEnd + 0.1 }));

  assert.deepEqual(late.pairPlan, early.pairPlan);
  assert.ok(early.transitionEnd < 120);
  assert.equal(late.transitionEnd, 120);
  assert.equal(late.transitionStart, 120 - early.fadeSeconds);
  assert.equal(late.reason, 'smart-pair-late-fallback');
});

test('legacy semantic fields cannot influence the live adapter pair decision', () => {
  const analysis = track('outgoing');
  const nextAnalysis = track('incoming');
  const plain = planTransition(smartOptions({ analysis, nextAnalysis }));
  const decorated = planTransition(smartOptions({
    analysis: {
      ...analysis,
      phraseBoundaries: [2, 4],
      mixOutTime: 4,
      mixOutCandidates: [{ time: 4, type: 'outro_start', score: 1 }]
    },
    nextAnalysis: {
      ...nextAnalysis,
      phraseBoundaries: [96],
      mixInTime: 96,
      mixInCandidates: [{ time: 96, type: 'main_drop', score: 1 }]
    }
  }));

  assert.deepEqual(decorated.pairPlan, plain.pairPlan);
  assert.equal(decorated.transitionStart, plain.transitionStart);
  assert.equal(decorated.incomingCueTime, plain.incomingCueTime);
});

test('missing tempo remains an explicit authoritative fallback reason', () => {
  const plan = planTransition(smartOptions({ analysis: track('outgoing', { bpm: 0 }) }));

  assert.equal(plan.pairPlan.status, 'fallback');
  assert.equal(plan.fallbackReason, 'outgoing-tempo');
  assert.equal(plan.transitionStyle, 'equal_power');
});

test('boundary fallbacks stay zero-overlap through the live adapter', () => {
  for (const [transitionClass, outgoingEnd] of [
    ['silence_trim', 116],
    ['normal_boundary', 120]
  ]) {
    const fallback = {
      transitionClass,
      outgoingStart: outgoingEnd,
      outgoingEnd,
      incomingCue: 0,
      durationSeconds: 0,
      strategy: 'boundary_handoff',
      transitionStyle: transitionClass,
      reason: `confidence-${transitionClass}`
    };
    const transition = transitionFromPairFallback({
      fallback,
      fallbackReason: fallback.reason,
      incoming: { handoff: 16 },
      diagnostics: { selected: null }
    }, track('outgoing'), track('incoming'), 120, outgoingEnd - 0.1, 1);

    assert.equal(transition.shouldStart, true);
    assert.equal(transition.transitionStart, outgoingEnd);
    assert.equal(transition.transitionEnd, outgoingEnd);
    assert.equal(transition.fadeSeconds, 0);
    assert.equal(transition.handoffDuration, 0);
    assert.equal(transition.transitionStyle, transitionClass);
    assert.strictEqual(transition.fallback, fallback);
  }
});

test('pending analysis keeps the reliable standard fallback', () => {
  const plan = planTransition(smartOptions({
    analysis: { ...track('outgoing'), status: 'ready', trackId: 'outgoing' },
    nextAnalysis: { status: 'loading', trackId: 'incoming' },
    fadeSeconds: 6
  }));

  assert.equal(plan.transitionStart, 114);
  assert.equal(plan.transitionStyle, 'equal_power');
  assert.equal(plan.reason, 'before-smart-analysis-fallback-window');
  assert.equal('pairPlan' in plan, false);
});

test('album playthrough context keeps the record gapless', () => {
  const plan = planTransition(smartOptions({
    albumSequential: true,
    currentTime: 119.7,
    currentTrack: { id: 'outgoing', albumId: 'album', durationSeconds: 120 },
    nextTrack: { id: 'incoming', albumId: 'album', durationSeconds: 120 }
  }));

  assert.equal(plan.transitionStyle, 'gapless');
  assert.equal(plan.shouldStart, true);
  assert.equal('pairPlan' in plan, false);
});

test('album siblings outside an album playthrough still use pair planning', () => {
  const plan = planTransition(smartOptions({
    currentTrack: { id: 'outgoing', albumId: 'album', durationSeconds: 120 },
    nextTrack: { id: 'incoming', albumId: 'album', durationSeconds: 120 }
  }));

  assert.ok(plan.pairPlan);
  assert.notEqual(plan.transitionStyle, 'gapless');
});

test('speech/live guards and short-track guards precede pair search', () => {
  const speech = planTransition(smartOptions({
    currentTrack: { id: 'outgoing', title: 'Live concert', durationSeconds: 120 }
  }));
  const short = planTransition(smartOptions({ duration: 30, currentTrack: { durationSeconds: 30 } }));

  assert.equal(speech.reason, 'blocked-speech-or-live');
  assert.equal(short.reason, 'short-duration-guard');
  assert.equal('pairPlan' in speech, false);
  assert.equal('pairPlan' in short, false);
});
