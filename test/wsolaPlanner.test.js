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

test('plans a sixteen-beat overlap ending at the outgoing mix-out', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.beats, 16);
  const outgoingOverlap = 16 * (60 / 126);
  assert.ok(plan.transitionStart <= 220 - outgoingOverlap + 0.001);
  const bar = (60 / 126) * 4;
  assert.ok(Math.abs(plan.transitionStart % bar) < 0.01 ||
    Math.abs((plan.transitionStart % bar) - bar) < 0.01);
  assert.ok(Math.abs(plan.transitionEnd - plan.transitionStart - outgoingOverlap) < 1e-9);
  assert.ok(Math.abs(plan.overlapSeconds - outgoingOverlap) < 1e-9);
  assert.equal(plan.stretchRatio, 1);
});

test('the incoming track enters at its mix-in point, snapped to a downbeat', () => {
  const nextAnalysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 20.5 });
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis,
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  const bar = (60 / 126) * 4;
  assert.ok(Math.abs(plan.incomingCueTime % bar) < 0.01);
  assert.ok(Math.abs(plan.incomingCueTime - 20.5) <= bar / 2 + 0.01);
  assert.ok(Math.abs(plan.incomingResumeTime - plan.incomingCueTime - plan.overlapSeconds) < 1e-9);
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

test('quantizes the bass swap onto a bar of the shared grid', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });
  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.bassSwapFraction, 0.75);
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
    nextAnalysis: analysisFor({ bpm: 126, duration: 30, mixInTime: 20 }),
    duration: 240,
    nextDuration: 30
  }).reason, 'incoming-too-short');

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
