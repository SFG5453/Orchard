/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

import assert from 'node:assert/strict';
import test from 'node:test';

import { normalizeTrackAnalysis } from '../shared/trackAnalysis.js';
import { chooseBassSwap } from '../src/audio/crossfade/transitionFlow.js';
import { raw } from './fixtures/workerAnalysis.js';

// Overlap second `t` plays outgoing 100 + t and incoming 10 + t; four bars of 2 s at 120 BPM.
const PAIR = Object.freeze({
  beats: 16, durationSeconds: 8, outgoingStart: 100, outgoingEnd: 108, incomingStart: 10, incomingEnd: 18,
  outgoingRatio: 1, incomingRatio: 1, alignment: 'end', tempoRamp: false
});
const analysis = (options) => normalizeTrackAnalysis(raw({ duration: 140, ...options }));
const singingAtTheMiddle = (time) => time >= 103 && time < 105.5 ? 0.95 : 0.05;

test('a swap with nothing to avoid stays in the middle', () => {
  assert.equal(chooseBassSwap(PAIR, analysis(), analysis()), 0.5);
});

test('the swap leaves a line the old singer is in the middle of', () => {
  assert.equal(chooseBassSwap(PAIR, analysis({ vocal: singingAtTheMiddle }), analysis()), 0.25);
});

test('an earlier swap waits for the new bass to be playing', () => {
  // The new song's beat comes in at incoming 13 s, after the early bar line at 12 s.
  const incoming = analysis({ beatFrom: 13 });
  assert.equal(chooseBassSwap(PAIR, analysis({ vocal: singingAtTheMiddle }), incoming), 0.75);
});

test('an earlier swap never hands the bars to a much weaker bass', () => {
  const outgoing = { ...analysis({ vocal: singingAtTheMiddle }), loudnessLufs: -8 };
  const weak = { ...analysis(), loudnessLufs: -16 };
  // The later bar is the only one left, and it keeps the stronger outgoing bass.
  assert.equal(chooseBassSwap(PAIR, outgoing, weak), 0.75);
});
