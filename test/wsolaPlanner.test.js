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

test('plans a fade that ends on the incoming drop', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  // A 20s intro at 126 BPM covers far more than four bars, so the fade is held
  // to the cap rather than spending the whole intro.
  assert.equal(plan.fadeBeats, 16);
  assert.equal(plan.beats, 16);
  const outgoingOverlap = plan.beats * (60 / 126);
  assert.ok(plan.transitionStart <= 220 - outgoingOverlap + 0.001);
  const bar = (60 / 126) * 4;
  assert.ok(Math.abs(plan.transitionStart % bar) < 0.01 ||
    Math.abs((plan.transitionStart % bar) - bar) < 0.01);
  assert.ok(Math.abs(plan.transitionEnd - plan.transitionStart - outgoingOverlap) < 1e-9);
  assert.equal(plan.stretchRatio, 1);
});

test('the outgoing track fades through the intro and is gone by the drop', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20.5 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  const bar = (60 / 126) * 4;
  assert.ok(Math.abs(plan.incomingDropTime - 20.5) <= bar / 2 + 0.01);
  // The whole overlap sits inside the intro and closes on the drop, so the
  // incoming arrangement never plays over a track that is still fading.
  assert.ok(Math.abs(plan.incomingCueTime + plan.overlapSeconds - plan.incomingDropTime) < 1e-9,
    `fade must end on the drop: cue ${plan.incomingCueTime} + ${plan.overlapSeconds} != ${plan.incomingDropTime}`);
  assert.ok(plan.incomingCueTime >= 0);
  assert.equal(plan.incomingResumeTime, plan.incomingDropTime);
  // One continuous equal-power fade rather than a bed followed by a tail.
  assert.equal(plan.handoffFraction, 0.5);
  assert.equal(plan.bedPosition, 0.5);
});

test('caps the fade when the incoming intro is very long', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 90 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.overlapSeconds <= 16.001, `overlap ${plan.overlapSeconds}`);
  // A long intro is not an invitation to play all of it; the drop stays put
  // and the fade starts later.
  assert.equal(plan.fadeBeats, 16);
  assert.ok(Math.abs(plan.incomingCueTime + plan.overlapSeconds - plan.incomingDropTime) < 1e-9);
});

test('slow tempos stay within the overlap ceiling', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 80, duration: 300, mixOutTime: 280 }),
    nextAnalysis: analysisFor({ bpm: 80, duration: 300, mixInTime: 60 }),
    duration: 300,
    nextDuration: 300
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.overlapSeconds <= 16.001, `overlap ${plan.overlapSeconds}`);
  assert.equal(plan.fadeBeats % 4, 0, 'the fade stays quantized to whole bars');
});

test('a short intro shortens the fade instead of refusing the pairing', () => {
  // Refusing here is what made this shape fail the first time it was tried:
  // a track that starts singing early lost beat-matching altogether.
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 3 }),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.fadeBeats <= 8, `fade ${plan.fadeBeats} beats should fit a 3s intro`);
  assert.ok(plan.fadeBeats >= 4, 'the fade bottoms out at one bar rather than vanishing');
  assert.ok(Math.abs(plan.incomingCueTime + plan.overlapSeconds - plan.incomingDropTime) < 1e-9);
});

test('shortens the fade rather than opening on lead-in silence', () => {
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
  assert.ok(Math.abs(plan.incomingCueTime + plan.overlapSeconds - plan.incomingDropTime) < 1e-9);
});

test('never overruns the drop when the intro is shorter than the one-bar floor', () => {
  // A cold open: the track makes sound barely a beat before its drop, so the
  // MIN_FADE_BEATS floor asks for four beats the intro cannot cover.
  const nextAnalysis = analysisFor({ bpm: 126, duration: 200, mixInTime: 20 });
  nextAnalysis.audibleStartTime = 18.4;
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis,
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.ok(plan.incomingCueTime >= 18.4 - 1e-9,
    `cue ${plan.incomingCueTime} must not precede the audible start`);
  // The invariant the whole shape rests on: the outgoing track reaches silence
  // exactly as the incoming drops, never a beat after it.
  assert.ok(Math.abs(plan.incomingCueTime + plan.overlapSeconds - plan.incomingDropTime) < 1e-9,
    `overlap must end on the drop: ${plan.incomingCueTime} + ${plan.overlapSeconds} != ${plan.incomingDropTime}`);
  assert.ok(plan.beats < 4, `expected the floor to yield to the intro, got ${plan.beats} beats`);
  assert.equal(plan.fadeBeats, plan.beats);
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

test('the low end hands over late in the fade', () => {
  const plan = planWsolaTransition({
    analysis: analysisFor({ bpm: 126, duration: 240 }),
    nextAnalysis: analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
    duration: 240,
    nextDuration: 200
  });
  assert.equal(plan.ok, true, plan.reason);
  // Past the equal-power crossing, so the incoming track does not gain the
  // bass while it is still fading up and arrive early.
  assert.ok(plan.bassSwapFraction > plan.handoffFraction);
  assert.ok(plan.bassSwapFraction < 1);
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

test('both tracks singing through the fade shortens it to one bar', () => {
  const plan = planWsolaTransition({
    analysis: withVocalMask(analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }), () => true),
    nextAnalysis: withVocalMask(
      analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }),
      () => true
    ),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.vocalClash, true);
  assert.equal(plan.fadeBeats, 4);
});

test('a clash confined to part of the window trims back a bar rather than jumping to the floor', () => {
  // The incoming track sings throughout its intro (always vocal-active), so it
  // is the outgoing side that decides whether each candidate window clashes.
  // The outgoing track sings across [204, 215) -- squarely inside the 16-beat
  // window ending at the mix-out anchor (220s, at 60 BPM 1s/beat) but mostly
  // outside the tail-anchored 12-beat window, which starts at 208. The 16-beat
  // check must clash; the 12-beat one must not. The old code checked only the
  // full candidate and, on any clash at all, fell straight to the one-bar
  // floor -- discarding three-quarters of a fade that a 12-beat window would
  // have rendered clash-free. That is what made transitions too short.
  const plan = planWsolaTransition({
    analysis: withVocalMask(
      analysisFor({ bpm: 60, duration: 240, mixOutTime: 220 }),
      (time) => time >= 204 && time < 215
    ),
    nextAnalysis: withVocalMask(analysisFor({ bpm: 60, duration: 200, mixInTime: 20 }), () => true),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.fadeBeats, 12, `expected the fade trimmed to 12 beats, got ${plan.fadeBeats}`);
  assert.equal(plan.vocalClash, false, 'the 12-beat window that was actually used must not itself be a clash');
});

test('instrumental pairings keep the full fade', () => {
  const plan = planWsolaTransition({
    analysis: withVocalMask(analysisFor({ bpm: 126, duration: 240, mixOutTime: 220 }), () => false),
    nextAnalysis: withVocalMask(analysisFor({ bpm: 126, duration: 200, mixInTime: 20 }), () => false),
    duration: 240,
    nextDuration: 200
  });

  assert.equal(plan.ok, true, plan.reason);
  assert.equal(plan.fadeBeats, 16);
  assert.equal(plan.vocalClash, false);
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
