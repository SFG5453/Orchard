/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import { normalizeTrackAnalysis } from '../shared/trackAnalysis.js';
import {
  planPairTransition
} from '../src/audio/crossfade/pairTransitionPlanner.js';
import { cleanPair, reasonCount } from './pairTransitionHelpers.js';

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

test('trustworthy downbeats can authorize conservative beatmatching without detected changes', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { boundaryTimes: [], meterConfidence: 0.15 },
    incoming: { boundaryTimes: [], meterConfidence: 0.15 }
  }));

  assert.equal(plan.transitionClass, 'conservative_beatmatched');
  assert.equal(plan.renderMode, 'native');
  assert.ok(plan.diagnostics.selected.sources.includes('downbeat-evidence'));
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

test('untrusted key evidence does not force a filtered beatmatch', () => {
  const plan = planPairTransition(cleanPair({
    outgoing: { key: '', keyConfidence: 0, chroma: [] },
    incoming: { key: '', keyConfidence: 0, chroma: [] }
  }));

  assert.equal(plan.renderMode, 'native');
  assert.ok(
    ['beatmatched_crossfade', 'bass_swap'].includes(plan.strategy),
    `expected an unfiltered beatmatch, got ${plan.strategy}`
  );
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


const choreographyFixtures = JSON.parse(
  fs.readFileSync(new URL('./fixtures/transitionChoreography.json', import.meta.url), 'utf8')
);

function fixturePair(name) {
  const data = choreographyFixtures[name];
  return {
    analysis: normalizeTrackAnalysis(data.outgoing),
    nextAnalysis: normalizeTrackAnalysis(data.incoming),
    duration: data.outgoing.duration,
    nextDuration: data.incoming.duration
  };
}

test('regression: Blinding Lights -> Dont Start Now is never beatmatched and has 0-4s overlap', () => {
  const plan = planPairTransition(fixturePair('blinding_lights_to_dont_start_now'));
  assert.notEqual(plan.transitionClass, 'full_beatmatched');
  assert.notEqual(plan.transitionClass, 'conservative_beatmatched');
  assert.notEqual(plan.strategy, 'beatmatched_crossfade');
  assert.ok(
    plan.durationSeconds >= 0 && plan.durationSeconds <= 4.0,
    `expected 0-4s overlap, got ${plan.durationSeconds}`
  );
  assert.ok(
    ['filtered_handoff', 'clean_cut', 'filtered_blend', 'boundary_handoff', 'short_fade'].includes(plan.strategy) ||
    plan.renderMode === 'boundary' ||
    plan.renderMode === 'live',
    `expected filtered handoff or cut, got ${plan.strategy}`
  );
  assert.ok(
    plan.fallback.durationSeconds <= 4.0,
    `fallback duration should be capped at 4s, got ${plan.fallback.durationSeconds}`
  );
});

test('fixture: safe instrumental blend receives 8-16 beat staged blend with incoming audibly present before arrival', () => {
  const plan = planPairTransition(fixturePair('safe_instrumental_blend'));
  assert.ok(['full_beatmatched', 'conservative_beatmatched'].includes(plan.transitionClass));
  assert.ok(plan.beats >= 8 && plan.beats <= 16, `expected 8-16 beats, got ${plan.beats}`);
  assert.ok(
    plan.incoming.start <= plan.incoming.handoff,
    `incoming cue (${plan.incoming.start}) should be <= arrival (${plan.incoming.handoff})`
  );
});

test('fixture: sustained vocal collision receives short filtered handoff or clean cut', () => {
  const plan = planPairTransition(fixturePair('sustained_vocal_collision'));
  assert.notEqual(plan.transitionClass, 'full_beatmatched');
  assert.ok(
    plan.durationSeconds <= 4.0 ||
    ['filtered_handoff', 'clean_cut', 'filtered_blend', 'boundary_handoff'].includes(plan.strategy),
    `expected short handoff or clean cut, got duration ${plan.durationSeconds} strategy ${plan.strategy}`
  );
});

test('fixture: weak evidence pair falls back cleanly', () => {
  const plan = planPairTransition(fixturePair('weak_evidence_pair'));
  assert.notEqual(plan.transitionClass, 'full_beatmatched');
  assert.notEqual(plan.transitionClass, 'conservative_beatmatched');
  assert.ok(['silence_trim', 'normal_boundary', 'simple_crossfade'].includes(plan.transitionClass));
});

test('every fallback has internally consistent cue, duration, and arrival geometry', () => {
  const cases = ['blinding_lights_to_dont_start_now', 'safe_instrumental_blend', 'sustained_vocal_collision', 'weak_evidence_pair'];
  for (const name of cases) {
    const plan = planPairTransition(fixturePair(name));
    const fallback = plan.fallback;
    assert.ok(fallback, `missing fallback for ${name}`);
    const expectedOutDuration = Math.round((fallback.outgoingEnd - fallback.outgoingStart) * 1e6) / 1e6;
    assert.ok(
      Math.abs(expectedOutDuration - fallback.durationSeconds) < 1e-4,
      `fallback geometry inconsistent for ${name}: outEnd - outStart = ${expectedOutDuration}, durationSeconds = ${fallback.durationSeconds}`
    );
  }
});
