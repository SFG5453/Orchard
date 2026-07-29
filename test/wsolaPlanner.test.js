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

// An analyzed mix-out is a silence cliff, so model it as one: the policy only
// honours an interior anchor when what it skips is silence rather than music.
function energyCurveFor(duration, silentAfter = 0) {
  const curve = [];
  for (let time = 0; time < duration; time += 1) {
    curve.push({ time, energy: silentAfter > 0 && time >= silentAfter ? 0 : 0.8 });
  }
  return curve;
}

function analysisFor({ bpm = 126, duration = 240, mixOutTime = 0, contentEndTime = 0, mixInTime = 0 } = {}) {
  return {
    bpm,
    beatInterval: 60 / bpm,
    beatConfidence: 0.9,
    energyCurve: energyCurveFor(duration, mixOutTime),
    duration,
    mixOutTime,
    contentEndTime: contentEndTime || duration,
    mixInTime,
    downbeats: downbeats(bpm, Math.floor(duration / ((60 / bpm) * 4))),
    mixInCandidates: []
  };
}

test('plans a pre-roll plus tail overlap ending at the outgoing mix-out', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  // A 20s intro at 126 BPM is 42 bar-aligned beats of available pre-roll, held
  // to the four-bar cap so the outgoing track is not bedded under for a
  // fifteen-second run-up to its own fade.
  assert.equal(plan.prerollBeats, 8);
  assert.equal(plan.tailBeats, 8);
  assert.equal(plan.beats, plan.prerollBeats + plan.tailBeats);
  const outgoingOverlap = plan.beats * (60 / 126);
  assert.ok(plan.transitionStart <= 220 - outgoingOverlap + 0.001);
  const bar = (60 / 126) * 4;
  assert.ok(Math.abs(plan.transitionStart % bar) < 0.01 ||
    Math.abs((plan.transitionStart % bar) - bar) < 0.01);
  assert.ok(Math.abs(plan.transitionEnd - plan.transitionStart - outgoingOverlap) < 1e-9);
  assert.ok(Math.abs(plan.overlapSeconds - outgoingOverlap) < 1e-9);
  assert.equal(plan.stretchRatio, 1);
});

test('the incoming track pre-rolls its intro and hands over on its drop', () => {
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
  // The incoming track starts well before it, so its intro plays underneath.
  assert.ok(plan.incomingCueTime < plan.incomingDropTime - 1,
    `expected a pre-roll, cue ${plan.incomingCueTime} drop ${plan.incomingDropTime}`);
  assert.ok(plan.incomingCueTime >= 0);
  // The handoff lands on the drop rather than the middle of the overlap.
  const handoffTime = plan.overlapSeconds * plan.handoffFraction;
  assert.ok(Math.abs(handoffTime - (plan.incomingDropTime - plan.incomingCueTime)) < 1e-9);
  assert.ok(Math.abs(plan.handoffFraction - plan.prerollBeats / plan.beats) < 1e-9);
  // The pre-roll spends only a quarter of the fade, so the outgoing track is
  // still essentially at level when the drop arrives and does its whole
  // audible fade over the tail.
  assert.ok(plan.bedPosition <= 0.3, `bed ${plan.bedPosition}`);
  assert.ok(Math.abs(plan.incomingResumeTime - plan.incomingCueTime - plan.overlapSeconds) < 1e-9);
});

test('caps the pre-roll when the incoming intro is very long', () => {
  const nextAnalysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 90 });
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis,
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.overlapSeconds <= 16.001, `overlap ${plan.overlapSeconds}`);
  // The drop stays put; the pre-roll shortens to fit the cap.
  assert.ok(Math.abs(plan.incomingDropTime - (plan.incomingCueTime + plan.overlapSeconds * plan.handoffFraction)) < 1e-9);
});

test('slow compatible tempos stay within the sixteen-second overlap ceiling', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 80, duration: 300, mixOutTime: 280 }),
    nextAnalysis: analysisFor({ bpm: 80, duration: 300, mixInTime: 60 }),
    duration: 300,
    nextDuration: 300
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.overlapSeconds <= 16.001, `overlap ${plan.overlapSeconds}`);
  assert.equal(plan.prerollBeats % 4, 0);
});

test('slow tempos shrink the tail in seconds rather than refusing the pairing', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 40, duration: 300, mixOutTime: 280 }),
    nextAnalysis: analysisFor({ bpm: 40, duration: 300, mixInTime: 60 }),
    duration: 300,
    nextDuration: 300
  });

  assert.equal(plan.ok, true, plan.reason);
  // At 40 BPM sixteen beats would be 24s of tail alone; the seconds cap
  // quantizes it down to a single bar instead of blowing the overlap ceiling.
  assert.equal(plan.tailBeats, 4);
  assert.ok(plan.overlapSeconds <= 16.001, `overlap ${plan.overlapSeconds}`);
});

test('shortens the pre-roll rather than opening on lead-in silence', () => {
  const nextAnalysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 20 });
  nextAnalysis.audibleStartTime = 14;
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis,
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.incomingCueTime >= 14 - 1e-9,
    `cue ${plan.incomingCueTime} must not precede the audible start`);
  assert.ok(Math.abs(plan.incomingDropTime - (plan.incomingCueTime + plan.overlapSeconds * plan.handoffFraction)) < 1e-9);
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

test('hands the low end over a bar after the drop', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });
  assert.equal(plan.ok, true, plan.reason);
  const swapBeat = plan.bassSwapFraction * plan.beats;
  assert.equal(swapBeat, plan.prerollBeats + 4);
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

// The vocal-activity mask indexes against energyCurve sample times.
function withVocalMask(analysis, isVocalAt) {
  return {
    ...analysis,
    vocalActivityMask: analysis.energyCurve.map((point) => (isVocalAt(point.time) ? 0.9 : 0.1))
  };
}

test('an outro of real music is mixed over rather than skipped', () => {
  // No silence cliff: the analyzer's mix-out marker sits 20s before content
  // end with music still playing there, so the overlap moves to the end.
  const analysis = analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 });
  const plan = planWsolaTransition({
    analysis: { ...analysis, energyCurve: energyCurveFor(240) },
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.mixOutType, 'content_end');
  assert.ok(plan.transitionEnd > 220, `transition ended at ${plan.transitionEnd}`);
});

test('shrinks the tail to one bar when both tracks sing through it', () => {
  // The outgoing track sings all the way to its mix-out; the incoming track
  // sings from its drop but its intro is instrumental.
  const plan = planWsolaTransition({
    analysis: withVocalMask(analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }), () => true),
    nextAnalysis: withVocalMask(
      analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
      (time) => time >= 20
    ),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.tailBeats, 4);
  assert.equal(plan.vocalClash.tail, true);
  assert.equal(plan.vocalClash.bed, false);
});

test('keeps the bed to one bar when the incoming intro also sings', () => {
  const plan = planWsolaTransition({
    analysis: withVocalMask(analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }), () => true),
    nextAnalysis: withVocalMask(analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }), () => true),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.vocalClash.bed, true);
  assert.equal(plan.prerollBeats, 4);
});

test('instrumental pairings keep the full pre-roll and tail', () => {
  const plan = planWsolaTransition({
    analysis: withVocalMask(analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }), () => false),
    nextAnalysis: withVocalMask(analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }), () => false),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.prerollBeats, 8);
  assert.equal(plan.tailBeats, 8);
  assert.deepEqual(plan.vocalClash, { bed: false, tail: false });
});

test('an untrusted beat grid cannot authorize a beat-matched render', () => {
  const incoming = analysisFor({ bpm: 126, duration: 200, mixInTime: 20 });
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }),
    nextAnalysis: { ...incoming, beatConfidence: 0.3 },
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, false);
  assert.equal(plan.reason, 'beat-confidence');
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
    nextAnalysis: analysisFor({ bpm: 126, duration: 24, mixInTime: 20 }),
    duration: 240,
    nextDuration: 24
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
