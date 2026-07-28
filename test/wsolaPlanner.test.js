import assert from 'node:assert/strict';
import test from 'node:test';

import {
  alignTempoOctave,
  incomingMixInPoint,
  planWsolaTransition
} from '../src/audio/crossfade/wsolaPlanner.js';

function downbeats(bpm, count, offset = 0) {
  const bar = (60 / bpm) * 4;
  return Array.from({ length: count }, (_, index) => offset + index * bar);
}

function analysisFor({ bpm = 126, duration = 240, mixOutTime = 0, contentEndTime = 0, mixInTime = 0 } = {}) {
  return {
    bpm,
    beatInterval: 60 / bpm,
    duration,
    mixOutTime,
    contentEndTime: contentEndTime || duration,
    mixInTime,
    downbeats: downbeats(bpm, Math.floor(duration / ((60 / bpm) * 4))),
    mixInCandidates: []
  };
}

test('plans a bed plus fade overlap ending at the outgoing mix-out', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  // A 20s intro at 126 BPM is 40 bar-aligned beats, enough for the full fade
  // and a bed held to the four-bar cap so the outgoing track is not bedded
  // under for a fifteen-second run-up to its own fade.
  assert.equal(plan.bedBeats, 16);
  assert.equal(plan.fadeBeats, 16);
  assert.equal(plan.beats, plan.bedBeats + plan.fadeBeats);
  const outgoingOverlap = plan.beats * (60 / 126);
  assert.ok(plan.transitionStart <= 220 - outgoingOverlap + 0.001);
  const bar = (60 / 126) * 4;
  assert.ok(Math.abs(plan.transitionStart % bar) < 0.01 ||
    Math.abs((plan.transitionStart % bar) - bar) < 0.01);
  assert.ok(Math.abs(plan.transitionEnd - plan.transitionStart - outgoingOverlap) < 1e-9);
  assert.ok(Math.abs(plan.overlapSeconds - outgoingOverlap) < 1e-9);
  assert.equal(plan.stretchRatio, 1);
});

test('the whole overlap is the incoming intro, ending on its drop', () => {
  const nextAnalysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 20.5 });
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis,
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  const bar = (60 / 126) * 4;
  // The drop is what the analyzer found, snapped to a downbeat.
  assert.ok(Math.abs(plan.incomingDropTime - 20.5) <= bar / 2 + 0.01);
  // The incoming track starts a whole overlap before it, so nothing but its
  // intro is ever heard against the outgoing track.
  assert.ok(Math.abs(plan.incomingDropTime - (plan.incomingCueTime + plan.overlapSeconds)) < 1e-9,
    `drop ${plan.incomingDropTime} must end the overlap from cue ${plan.incomingCueTime}`);
  assert.ok(plan.incomingCueTime >= 0);
  // The fade closes the overlap: the outgoing track is gone on the drop.
  const fadeStart = plan.overlapSeconds * plan.handoffFraction;
  assert.ok(Math.abs(plan.overlapSeconds - fadeStart - plan.fadeBeats * (60 / 126)) < 1e-9);
  assert.ok(Math.abs(plan.handoffFraction - plan.bedBeats / plan.beats) < 1e-9);
  // The bed spends only a quarter of the fade curve, so the outgoing track is
  // still essentially at level when the fade starts.
  assert.ok(plan.bedPosition <= 0.3, `bed ${plan.bedPosition}`);
  // Playback resumes on the drop, where the buffer hands back to the element.
  assert.ok(Math.abs(plan.incomingResumeTime - plan.incomingDropTime) < 1e-9);
});

test('caps the bed when the incoming intro is very long', () => {
  const nextAnalysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 90 });
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis,
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.overlapSeconds <= 20.001, `overlap ${plan.overlapSeconds}`);
  // The drop stays put; the incoming track is cued later to fit the cap.
  assert.ok(Math.abs(plan.incomingDropTime - (plan.incomingCueTime + plan.overlapSeconds)) < 1e-9);
});

test('refuses slow-tempo plans whose fixed tail cannot fit the overlap ceiling', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 40, duration: 300, mixOutTime: 280 }),
    nextAnalysis: analysisFor({ bpm: 40, duration: 300, mixInTime: 60 }),
    duration: 300,
    nextDuration: 300
  });

  assert.equal(plan.ok, false);
  assert.equal(plan.reason, 'overlap-too-long');
});

test('shortens the overlap rather than fading against lead-in silence', () => {
  const nextAnalysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 20 });
  nextAnalysis.audibleStartTime = 14;
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis,
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.incomingCueTime >= 0);
  // A six-second intro cannot cover the full sixteen-beat fade, so the fade
  // shortens with it and the whole overlap still ends on the drop.
  assert.ok(plan.fadeBeats < 16, `fade ${plan.fadeBeats}`);
  assert.ok(Math.abs(plan.incomingDropTime - (plan.incomingCueTime + plan.overlapSeconds)) < 1e-9);
  // The bed may open on lead-in silence -- the outgoing track is still at full
  // level there -- but the fade never does.
  const fadeStart = plan.incomingCueTime + plan.overlapSeconds * plan.handoffFraction;
  assert.ok(fadeStart >= 14 - 1e-9, `fade starts at ${fadeStart}, before the audible start`);
});

test('prefers an analyzed drop over the plain mix-in time', () => {
  const analysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 12 });
  analysis.mixInCandidates = [
    { time: 45, score: 0.4, type: 'phrase' },
    { time: 30.476, score: 0.2, type: 'main_drop' }
  ];
  const point = incomingMixInPoint(analysis);
  assert.ok(Math.abs(point - 30.476) < 1, `expected drop-anchored mix-in, got ${point}`);
});

test('octave-doubles a half-time incoming tempo onto the shared grid', () => {
  assert.equal(alignTempoOctave(126, 63), 126);
  assert.equal(alignTempoOctave(126, 252), 126);
  assert.equal(alignTempoOctave(100, 98), 98);

  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 63, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });
  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.incomingBpm, 126);
  assert.equal(plan.stretchRatio, 1);
});

test('hands the low end over a bar into the fade', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });
  assert.equal(plan.ok, true, plan.reason);
  const swapBeat = plan.bassSwapFraction * plan.beats;
  assert.equal(swapBeat, plan.bedBeats + 4);
  assert.equal(swapBeat % 4, 0);
  assert.ok(plan.bassSwapFraction > plan.handoffFraction);
});

test('slice anchors map media times into the sliced buffers', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });
  assert.equal(plan.ok, true, plan.reason);
  assert.ok(Math.abs(plan.outgoingSlice.start + plan.outgoingSlice.anchor - plan.transitionStart) < 1e-9);
  assert.ok(Math.abs(plan.incomingSlice.start + plan.incomingSlice.anchor - plan.incomingCueTime) < 1e-9);
  assert.ok(plan.outgoingSlice.end >= plan.transitionEnd);
  assert.ok(plan.incomingSlice.end >= plan.incomingResumeTime);
  assert.ok(plan.incomingSlice.end <= 200);
});

test('refuses pairings that cannot be rendered transparently', () => {
  const base = analysisFor({ bpm: 126, duration: 240 });
  const incoming = analysisFor({ bpm: 126, duration: 200, mixInTime: 20 });

  assert.equal(planWsolaTransition({
    analysis: { ...base, bpm: 0 },
    nextAnalysis: incoming,
    duration: 240,
    nextDuration: 200
  }).reason, 'outgoing-tempo');

  assert.equal(planWsolaTransition({
    analysis: base,
    nextAnalysis: analysisFor({ bpm: 100, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  }).reason, 'tempo-distance');

  assert.equal(planWsolaTransition({
    analysis: base,
    nextAnalysis: analysisFor({ bpm: 126, duration: 22, mixInTime: 20 }),
    duration: 240,
    nextDuration: 22
  }).reason, 'incoming-too-short');

  assert.equal(planWsolaTransition({
    analysis: base,
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 1.5 }),
    duration: 240,
    nextDuration: 200
  }).reason, 'incoming-intro-too-short');

  assert.equal(planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 12 }),
    nextAnalysis: incoming,
    duration: 12,
    nextDuration: 200
  }).reason, 'outgoing-too-short');

  assert.equal(planWsolaTransition({
    analysis: base,
    nextAnalysis: { ...incoming, mixInTime: 0, mixInCandidates: [] },
    duration: 240,
    nextDuration: 200
  }).reason, 'incoming-mix-in');
});
