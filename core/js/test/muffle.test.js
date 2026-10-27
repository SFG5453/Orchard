/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

import assert from 'node:assert/strict';
import test from 'node:test';

import { planPairTransition } from '../src/audio/crossfade/pairTransitionPlanner.js';
import { TECHNIQUE } from '../src/audio/crossfade/transitionArrangement.js';
import { explainRefusal } from '../src/audio/crossfade/transitionExplain.js';
import { raw } from './fixtures/workerAnalysis.js';

const SHARP = Object.freeze({ key: 'C♯ major', keyConfidence: 0.95, chroma: [0, 1, 0, 0, 0, 0.7, 0, 0, 0.5, 0, 0, 0] });

const plan = (outgoing, incoming) => planPairTransition({
  analysis: outgoing, nextAnalysis: incoming, duration: outgoing.duration, nextDuration: incoming.duration, tempoRamp: true
});
const outgoing = () => raw({ boundaries: [104, 112] });
const clashing = (extra = {}) => ({ ...raw({ boundaries: [16, 32] }), ...SHARP, ...extra });

test('a trusted distant key leaves behind a muffle', () => {
  const result = plan(outgoing(), clashing());
  assert.equal(result.renderMode, 'native');
  assert.equal(result.strategy, 'muffle_swap');
  assert.equal(result.technique, TECHNIQUE.MUFFLE);
  const { outgoingFade, incomingFade, bassSwapFraction } = result.arrangement;
  // The new song is in by the swap; the muffled old one is gone two beats later.
  assert.equal(incomingFade[1], bassSwapFraction);
  assert.equal(outgoingFade[0], bassSwapFraction);
  assert.ok(Math.abs((outgoingFade[1] - outgoingFade[0]) * result.beats - 2) < 1e-6);
});

test('matching keys keep the ordinary bass swap', () => {
  assert.equal(plan(outgoing(), raw({ boundaries: [16, 32] })).technique, TECHNIQUE.BASS_SWAP);
});

test('a muffle into a much weaker beat plays out', () => {
  const louder = { ...outgoing(), loudnessLufs: -8 };
  const result = plan(louder, clashing({ loudnessLufs: -16 }));
  assert.equal(result.renderMode, 'boundary');
  assert.equal(result.fallbackReason, 'needs-filter');
  assert.equal(plan(louder, clashing({ loudnessLufs: -10 })).technique, TECHNIQUE.MUFFLE);
});

test('a muffle never fades in the first notes of the new song', () => {
  for (const start of [0, 1.5, 3]) {
    const result = plan(outgoing(), clashing({ audibleStartTime: start }));
    assert.equal(result.technique, TECHNIQUE.MUFFLE);
    const heard = result.incoming.start +
      result.arrangement.incomingFade[0] * result.durationSeconds * result.incoming.tempoRatio;
    assert.ok(heard - start >= 2 - 1e-6, `fades in ${heard - start} s into a song starting at ${start}`);
  }
});

test('a tempo refusal names the section tempos where a beat switch moved them', () => {
  const fit = { outgoingBpm: 94, incomingBpm: 95, deviation: 0.011 };
  const switched = explainRefusal({ fit, fits: { far: 4 }, nearest: { outgoingBpm: 133.3, incomingBpm: 95, deviation: 0.403 } });
  assert.equal(switched.code, 'tempo-distance');
  assert.deepEqual(switched.bpm, [133.3, 95]);
  assert.match(switched.detail, /^133\.3 vs 95 BPM where the songs meet, 40\.3% apart \(songs are 94 and 95 BPM\)$/);
  const steady = explainRefusal({ fit: { ...fit, incomingBpm: 128, deviation: 0.36 }, fits: { far: 4 },
    nearest: { outgoingBpm: 94, incomingBpm: 128, deviation: 0.36 } });
  assert.equal(steady.detail, '94 vs 128 BPM, 36.0% apart');
});
