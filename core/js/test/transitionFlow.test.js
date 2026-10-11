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
import {
  interpolateTrackFrame,
  normalizeTrackAnalysis,
  summarizeTrackWindow
} from '../shared/trackAnalysis.js';
import { generateTransitionCandidates } from '../src/audio/crossfade/pairTransitionEvidence.js';
import { raw } from './fixtures/workerAnalysis.js';
import { planPairTransition } from '../src/audio/crossfade/pairTransitionPlanner.js';
import { transitionFromPairFallback } from '../src/audio/crossfade/transitionPlanner.js';
import {
  bassArrival,
  bassSwapFraction,
  beatFlow,
  productionContrast
} from '../src/audio/crossfade/transitionFlow.js';

const analysis = (options) => normalizeTrackAnalysis(raw(options));

// Sixteen beats at 120 BPM: eight seconds ending at the outgoing anchor.
function pair({ incomingStart, incomingEnd, anchor, beats = 16, beatmatched = true, duration = 8 }) {
  return {
    durationSeconds: duration, beats, beatmatched,
    outgoingStart: 100 - duration, outgoingEnd: 100,
    incomingStart, incomingEnd, incomingAnchor: anchor,
    outgoingRatio: 1, incomingRatio: 1
  };
}

test('measured vocal curves replace the frame estimate where they reach', () => {
  const track = analysis({ duration: 40, vocal: () => 0.1 });
  // Frames claim 0.9; the model heard an instrumental.
  assert.equal(interpolateTrackFrame(track, 'vocal', 12), 0);
  assert.equal(summarizeTrackWindow(track, 10, 20).vocal, 0);
  const sung = analysis({ duration: 40, vocal: (t) => (t > 20 ? 0.6 : 0.1) });
  assert.equal(interpolateTrackFrame(sung, 'vocal', 30), 1);
  // Past the curve the frame estimate still answers.
  const short = normalizeTrackAnalysis({ ...raw({ duration: 40 }), vocalCurve: { start: 0, step: 0.25, values: [0.1, 0.1] } });
  assert.equal(interpolateTrackFrame(short, 'vocal', 30), 0.9);
});

test('a beatless intro drops its beat on the swap', () => {
  const outgoing = analysis({ beatFrom: 0 });
  const incoming = analysis({ beatFrom: 20 });
  const ending = beatFlow(pair({ incomingStart: 12, incomingEnd: 20, anchor: 20 }), outgoing, incoming);
  const dropped = beatFlow(pair({ incomingStart: 16, incomingEnd: 24, anchor: 20 }), outgoing, incoming);
  assert.ok(ending.lateDrop > 0.8);
  assert.equal(dropped.lateDrop, 0);
  assert.ok(dropped.score > ending.score + 0.25);
});

test('waiting for the beat after the old song leaves fails the flow', () => {
  const flow = beatFlow(
    pair({ incomingStart: 0, incomingEnd: 8, anchor: 8 }),
    analysis({ beatFrom: 0 }),
    analysis({ beatFrom: 30 })
  );
  assert.ok(flow.waitSeconds >= 8);
  assert.equal(flow.score, 0);
});

test('a beatless outro has no beat to lose', () => {
  const flow = beatFlow(
    pair({ incomingStart: 0, incomingEnd: 8, anchor: 8 }),
    analysis({ beatUntil: 80 }),
    analysis({ beatFrom: 0 })
  );
  assert.equal(flow.worstDeficit, 0);
  assert.equal(flow.lateDrop, 0);
  // Only the small cost of starting eight seconds past the first beat remains.
  assert.ok(flow.score > 0.9);
});

test('a beat that stopped just before the mix still counts as lost', () => {
  const incoming = analysis({ beatFrom: 0 });
  const mix = pair({ incomingStart: 0, incomingEnd: 8, anchor: 8 });
  // The overlap starts at 92 s: one second after this beat stopped, twelve after that one.
  const recent = beatFlow(mix, analysis({ beatUntil: 91 }), incoming);
  const settled = beatFlow(mix, analysis({ beatUntil: 80 }), incoming);
  assert.ok(recent.worstDeficit > 0.5);
  assert.equal(settled.worstDeficit, 0);
});

test('a cut only needs the beat right after the switch', () => {
  const cut = pair({ incomingStart: 18.5, incomingEnd: 20, anchor: 20, beats: 0, beatmatched: false, duration: 1.5 });
  const flow = beatFlow(cut, analysis({ beatFrom: 0 }), analysis({ beatFrom: 20 }));
  assert.ok(flow.score > 0.95);
});

test('the bass changes hands on the first bar where the incoming has one', () => {
  const ready = analysis({ beatFrom: 0 });
  assert.equal(bassSwapFraction(pair({ incomingStart: 12, incomingEnd: 20, anchor: 20 }), ready), 0.5);
  // Bass arrives three bars into a four-bar overlap.
  const late = analysis({ beatFrom: 17.5 });
  assert.equal(bassSwapFraction(pair({ incomingStart: 12, incomingEnd: 20, anchor: 20 }), late), 0.75);
  assert.equal(bassSwapFraction(pair({ incomingStart: 12, incomingEnd: 20, anchor: 20 }), analysis({ duration: 40 })), 0.5);
});

test('clashing productions keep the natural boundary', () => {
  const outgoing = raw({ sub: -0.8, boundaries: [104, 112] });
  const incoming = raw({ sub: 0.9, beatFrom: 8, boundaries: [16, 32] });
  assert.equal(productionContrast(normalizeTrackAnalysis(outgoing), normalizeTrackAnalysis(incoming)), 1.7);
  const plan = planPairTransition({ analysis: outgoing, nextAnalysis: incoming, duration: 120, nextDuration: 120, tempoRamp: true });
  assert.equal(plan.transitionClass, 'normal_boundary');
  assert.equal(plan.renderMode, 'boundary');
  assert.equal(plan.fallbackReason, 'style-contrast');
  assert.equal(plan.fallback.outgoingEnd, 120);
  const live = transitionFromPairFallback(plan, outgoing, incoming, 120, 119.9);
  assert.equal(live.markerVisible, false);
  assert.equal(live.shouldStart, false);
  assert.equal(live.reason, 'style-contrast');
  const matched = planPairTransition({
    analysis: { ...outgoing, subBassRatio: 0.7 }, nextAnalysis: incoming, duration: 120, nextDuration: 120, tempoRamp: true
  });
  assert.equal(matched.renderMode, 'native');
});

test('a moderate style contrast blends only when the blend is clean', () => {
  const plan = (vocal) => planPairTransition({
    analysis: raw({ sub: -0.35, vocal, boundaries: [104, 112] }),
    nextAnalysis: raw({ sub: 0.35, vocal, boundaries: [16, 32] }),
    duration: 120, nextDuration: 120, tempoRamp: true
  });
  const clean = plan(() => 0.1);
  assert.equal(clean.productionContrast, 0.7);
  assert.equal(clean.renderMode, 'native');
  // Two voices at once would need the filter on top of the style gap.
  const crowded = plan(() => 0.9);
  assert.equal(crowded.transitionClass, 'normal_boundary');
  assert.equal(crowded.fallbackReason, 'style-contrast');
});

test('plans stay inside the decoded render windows', () => {
  const outgoing = raw({ boundaries: [64, 104, 112] });
  const incoming = raw({ boundaries: [16, 58, 70] });
  const plan = planPairTransition({
    analysis: outgoing, nextAnalysis: incoming, duration: 120, nextDuration: 120, tempoRamp: true,
    outgoingWindowStart: 70, incomingWindowEnd: 60
  });
  assert.ok(plan.incoming.handoff <= 59.75);
  assert.ok(plan.outgoing.start >= 70);
  for (const candidate of plan.diagnostics.topCandidates) assert.ok(candidate.incomingHandoff <= 59.75);
});

test('the beat entry after a beatless intro becomes a mix-in anchor', () => {
  const incoming = analysis({ beatFrom: 21 });
  const entry = generateTransitionCandidates(incoming, 'incoming').find((c) => c.source === 'beat-entry');
  assert.ok(entry);
  // Snapped to the downbeat at 22 s (four beats of 0.5 s per bar).
  assert.equal(entry.anchorTime, 22);
  assert.ok(!generateTransitionCandidates(analysis({ beatFrom: 0 }), 'incoming')
    .some((c) => c.source === 'beat-entry' && c.anchorTime > 1));
});

test('two measured voices singing through the overlap play out', () => {
  const plan = planPairTransition({
    analysis: raw({ vocal: () => 0.9, boundaries: [104, 112] }),
    nextAnalysis: raw({ vocal: () => 0.9, boundaries: [16, 32] }),
    duration: 120, nextDuration: 120, tempoRamp: true
  });
  assert.equal(plan.transitionClass, 'normal_boundary');
  assert.equal(plan.fallbackReason, 'voice-over-voice');
});

test('an outgoing singer cut off mid-line plays out', () => {
  const plan = (vocal) => planPairTransition({
    analysis: raw({ vocal, boundaries: [104, 112] }),
    nextAnalysis: raw({ vocal: () => 0.1, boundaries: [16, 32] }),
    duration: 120, nextDuration: 120, tempoRamp: true
  });
  const cut = plan(() => 0.9);
  assert.equal(cut.transitionClass, 'normal_boundary');
  assert.equal(cut.fallbackReason, 'voice-cut');
  assert.equal(plan(() => 0.1).renderMode, 'native');
});

test('a held bassline after a kick-only intro is the drop, even half a bar off the grid', () => {
  const track = raw({ duration: 60 });
  // Kicks every second until 21 s, then a held bass; bars start every 2 s at 0.
  track.bassCurve.values = track.bassCurve.values.map((_, index) => {
    const time = index * 0.25;
    return time >= 21 ? 1 : (time % 1 < 0.25 ? 1 : 0.05);
  });
  const arrival = bassArrival(normalizeTrackAnalysis(track), { start: 0, end: 60 });
  assert.equal(arrival.time, 21);
  assert.equal(bassArrival(analysis({ duration: 60 }), { start: 0, end: 60 }), null);
});
