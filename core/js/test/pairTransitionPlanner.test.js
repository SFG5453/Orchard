/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  PAIR_TRANSITION_POLICY,
  planPairTransition
} from '../src/audio/crossfade/pairTransitionPlanner.js';
import { cleanPair, reasonCount } from './pairTransitionHelpers.js';

test('a glide-capable renderer beatmatches a 6% tempo gap', () => {
  const pair = cleanPair({ incoming: { bpm: 127 } });
  assert.notEqual(planPairTransition(pair).renderMode, 'native');

  const plan = planPairTransition({ ...pair, tempoRamp: true });
  assert.equal(plan.renderMode, 'native');
  assert.equal(plan.tempoRamp, true);
  assert.ok(plan.outgoing.tempoRatio > 1);
  assert.ok(plan.incoming.tempoRatio < 1);
  // Each side consumes its own beats; output time follows the log-mean tempo.
  const beats = plan.beats;
  assert.ok(Math.abs(plan.outgoing.end - plan.outgoing.start - beats * 60 / 120) < 1e-4);
  assert.ok(Math.abs(plan.incoming.handoff - plan.incoming.start - beats * 60 / 127) < 1e-4);
  assert.ok(Math.abs(plan.durationSeconds - beats * 60 / plan.targetBpm) < 1e-5);
  assert.ok(plan.incoming.handoff - plan.durationSeconds >= 0);
  // An 11.8% glide sounded forced in listening tests, so it plays out.
  assert.equal(planPairTransition({ ...cleanPair({ incoming: { bpm: 134 } }), tempoRamp: true }).renderMode, 'boundary');
});

test('chooses a full beatmatched transition for a clean compatible pair', () => {
  const plan = planPairTransition(cleanPair());

  assert.equal(plan.status, 'planned');
  assert.equal(plan.transitionClass, 'full_beatmatched');
  assert.equal(plan.renderMode, 'native');
  assert.ok(plan.confidence >= PAIR_TRANSITION_POLICY.confidence.full);
  assert.ok(plan.outgoing.end > plan.outgoing.start);
  assert.equal(plan.incoming.resume, plan.incoming.handoff);
  assert.ok(['beatmatched_crossfade', 'bass_swap'].includes(plan.strategy));
  assert.equal(plan.fallback.transitionClass, 'normal_boundary');

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
    'flow',
    'harmonic',
    'spectral',
    'stability',
    'structure',
    'tempo',
    'vocal'
  ]);
  assert.equal(plan.fallbackReason, '');
});

test('sustained spectral vocal risk that needs the filter plays out', () => {
  const plan = planPairTransition(cleanPair({
    // Cover the complete outgoing/incoming candidate windows so there is no
    // clean alternate downbeat that can legitimately avoid the collision.
    outgoing: { vocal: (time) => time >= 70 ? 0.92 : 0.05 },
    incoming: { vocal: (time) => time <= 60 ? 0.9 : 0.05 }
  }));

  assert.equal(plan.transitionClass, 'normal_boundary');
  assert.ok(reasonCount(plan, 'vocal-collision') > 0);
  assert.equal(plan.renderMode, 'boundary');
  assert.equal(plan.fallbackReason, 'needs-filter');
});

test('one-sided vocals over an instrumental bed remain beatmatch eligible', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { vocal: () => 0.92 },
    incoming: { vocal: () => 0.03 }
  }));

  assert.ok(
    ['full_beatmatched', 'conservative_beatmatched'].includes(plan.transitionClass),
    `expected a beatmatched transition, got ${plan.transitionClass}`
  );
  assert.equal(plan.renderMode, 'native');
  assert.equal(reasonCount(plan, 'vocal-collision'), 0);
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

test('plays out the captured 135-to-85.5 BPM stress pair instead of fading', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { bpm: 135 },
    incoming: { bpm: 85.5 }
  }));

  assert.equal(plan.transitionClass, 'normal_boundary');
  assert.equal(plan.renderMode, 'boundary');
  assert.equal(plan.fallbackReason, 'tempo-distance');
  assert.equal(plan.fallback.durationSeconds, 0);
});

test('does not authorize beatmatching from a low-confidence grid', () => {
  const plan = planPairTransition(cleanPair({ outgoing: { beatConfidence: 0.3 } }));

  assert.notEqual(plan.renderMode, 'native');
  assert.ok(reasonCount(plan, 'beat-confidence') > 0);
});

test('plays out when the incoming track has no usable intro runway', () => {
  const plan = planPairTransition(cleanPair({
    incoming: { boundaryTimes: [0], downbeats: [] }
  }));

  assert.ok(['silence_trim', 'normal_boundary'].includes(plan.transitionClass));
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
