import assert from 'node:assert/strict';
import test from 'node:test';

import { mergeBpmMetadata } from '../src/audio/crossfade/bpmMetadata.js';
import { planTransition } from '../src/audio/crossfade/transitionPlanner.js';

// An interior mix-out is only honoured when what it skips is silence rather
// than music, so tests that expect one have to supply the silence.
function silentAfter(duration, cliff) {
  const curve = [];
  for (let time = 0; time < duration; time += 1) {
    curve.push({ time, energy: time >= cliff ? 0 : 0.8 });
  }
  return curve;
}

// The outgoing track fades across the whole overlap and reaches silence as the
// incoming one drops, so the fade closes on the drop. A track whose intro is
// shorter than the overlap cues at zero instead, which shortens the run-up
// rather than moving the drop away from where the analyzer found it.
function assertFadeClosesOnDrop(plan) {
  const landing = plan.incomingCueTime + plan.fadeSeconds * plan.incomingPlaybackRate;
  assert.ok(
    Math.abs(landing - plan.incomingHandoffTime) < 0.001 || plan.incomingCueTime === 0,
    `fade should close on the drop: ${landing} vs ${plan.incomingHandoffTime}`
  );
  assert.equal(plan.handoffStartSeconds, 0, 'the fade spans the overlap');
}

test('smart transitions honor analyzed content end and structural boundaries', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatInterval: 0.5,
      beatConfidence: 0.2,
      contentEndTime: 180,
      downbeats: [169, 171, 173, 175, 177, 179],
      phraseBoundaries: [157, 173],
      vocalProbability: 0.75
    },
    currentTime: 176,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: {
      bpm: 90,
      audibleStartTime: 0.4,
      vocalProbability: 0.8
    },
    nextTrack: { id: 'next', durationSeconds: 240 }
  });

  assert.equal(plan.transitionEnd, 180);
  // Four bars at 120 BPM, snapped to the downbeat at 175.
  assert.equal(plan.transitionStart, 175);
  assert.equal(plan.fadeSeconds, 5);
  assert.equal(plan.transitionBeats, 8);
  assert.ok(Math.abs(plan.handoffStartSeconds + plan.handoffDuration - plan.fadeSeconds) < 0.001);
  assert.equal(plan.shouldStart, true);
});

test('same-tempo phrase switches use an AutoMix-style blend', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 180,
      downbeats: [150, 152, 154, 156, 158, 160, 162, 164, 166, 168, 170, 172, 174, 176, 178],
      firstBeat: 0,
      key: 'C major'
    },
    currentTime: 178,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: {
      bpm: 120,
      beatConfidence: 0.9,
      audibleStartTime: 0.7,
      beats: [0.7, 1.2],
      downbeats: [0.2, 2.2],
      key: 'C major'
    },
    nextTrack: { id: 'next', durationSeconds: 200 }
  });

  assert.equal(plan.transitionStyle, 'dj_blend');
  assert.equal(plan.transitionEnd, 178);
  // The overlap now covers only the incoming intro (drop at ~2.2s), so with
  // no tail seconds the beat count reflects the intro length, not a padded
  // overlap. At 120 BPM (~0.5s/beat) and a ~1.5s intro this is a few beats.
  assert.ok(plan.transitionBeats >= 2);
  assert.ok(plan.fadeSeconds >= 1);
  assert.equal(plan.bassSwap, true);
  // The overlap now covers only the intro, so the incoming cue lands at the
  // first downbeat (0.2) rather than clamping to the track start.
  assert.ok(plan.incomingCueTime >= 0);
  assertFadeClosesOnDrop(plan);
  assert.equal(plan.shouldStart, true);
});

test('catalog-only tempo cannot authorize a beat-aligned phrase switch', () => {
  const catalogAnalysis = mergeBpmMetadata({}, {
    bpm: 120,
    tempoConfidence: 0.82,
    key: 'C',
    keyConfidence: 0.82,
    source: 'GetSongBPM'
  });
  const plan = planTransition({
    analysis: {
      ...catalogAnalysis,
      contentEndTime: 180,
      downbeats: []
    },
    currentTime: 170,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: {
      ...catalogAnalysis,
      downbeats: []
    },
    nextTrack: { id: 'next', durationSeconds: 200 }
  });

  assert.ok(!['dj_switch', 'dj_blend'].includes(plan.transitionStyle));
  assert.equal(catalogAnalysis.beatConfidence, 0);
  assert.equal(catalogAnalysis.tempoConfidence, 0.82);
});

test('DJ transitions prefer the analyzed interior mix-in downbeat', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 180,
      downbeats: [140, 142, 144, 146, 148, 150, 152, 154, 156, 158, 160, 162, 164, 166, 168, 170, 172, 174, 176, 178],
      key: 'C minor'
    },
    currentTime: 150,
    currentTrack: { id: 'current', durationSeconds: 180 },
    duration: 180,
    mode: 'smart',
    nextAnalysis: {
      bpm: 120,
      beatConfidence: 0.9,
      key: 'C minor',
      mixInTime: 20.9,
      mixInConfidence: 0.85,
      downbeats: [0.7, 20.7, 22.7]
    },
    nextTrack: { id: 'next', durationSeconds: 210 }
  });

  assertFadeClosesOnDrop(plan);
  assert.ok(plan.transitionBeats >= 8);
});

test('smart transitions keep a six-second floor on fast tracks', () => {
  const plan = planTransition({
    analysis: {
      bpm: 160,
      beatConfidence: 0.35,
      contentEndTime: 180,
      downbeats: [170, 171.5, 173, 174.5, 176, 177.5, 179],
      key: 'C major'
    },
    currentTrack: { id: 'current', durationSeconds: 180 },
    duration: 180,
    mode: 'smart',
    nextAnalysis: {
      bpm: 160,
      beatConfidence: 0.35,
      key: 'G major'
    },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.ok(plan.fadeSeconds >= 6, `fade ${plan.fadeSeconds}`);
});

test('filtered DJ transitions cap long intro pre-rolls at four bars', () => {
  const plan = planTransition({
    analysis: {
      bpm: 91.7354,
      beatConfidence: 0.3145,
      contentEndTime: 266.5,
      mixOutTime: 258.4,
      downbeats: [222.76, 225.38, 227.99, 230.61, 233.23],
      phraseBoundaries: [214.61, 235.54],
      key: 'C minor',
      keyConfidence: 0.0279,
      vocalProbability: 0.75
    },
    currentTime: 240,
    currentTrack: { id: 'current', durationSeconds: 272 },
    duration: 272,
    mode: 'smart',
    nextAnalysis: {
      bpm: 92.1317,
      beatConfidence: 0.2605,
      audibleStartTime: 0,
      mixInTime: 22.9108,
      key: 'B♭ minor',
      keyConfidence: 0.0089,
      vocalProbability: 0.8
    },
    nextTrack: { id: 'next', durationSeconds: 233 }
  });

  assert.equal(plan.transitionStyle, 'dj_blend');
  assert.equal(plan.incomingHandoffTime, 22.9108);
  assertFadeClosesOnDrop(plan);
  assert.ok(plan.handoffDuration > 2);
  // Four bars at ~92 BPM is about 10.4s, well inside the seconds rail.
  assert.ok(plan.fadeSeconds <= 12, `fade ${plan.fadeSeconds}`);
  assert.ok(plan.fadeSeconds / (60 / 91.7354) <= 16.5, 'overlap must stay within four bars');
  assert.equal(plan.transitionEnd, 258.4);
});

test('phrase alignment cannot stretch a smart transition past four bars', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 200,
      downbeats: [176, 178, 180, 182, 184, 186, 188, 190, 192, 194, 196, 198],
      key: 'C major'
    },
    currentTime: 180,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: {
      bpm: 120,
      beatConfidence: 0.9,
      mixInTime: 30,
      key: 'C major'
    },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionStyle, 'dj_blend');
  assert.ok(plan.fadeSeconds <= 8.01, `fade ${plan.fadeSeconds}`);
});

test('missing tempo degrades to a plain crossfade at the analyzed mix-out', () => {
  const plan = planTransition({
    analysis: {
      bpm: 0,
      contentEndTime: 190,
      mixOutTime: 170,
      energyCurve: silentAfter(190, 170)
    },
    currentTime: 150,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    fadeSeconds: 6,
    mode: 'smart',
    nextAnalysis: { bpm: 120, beatConfidence: 0.9, audibleStartTime: 1.2 },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionStyle, 'equal_power');
  assert.equal(plan.transitionEnd, 170);
  assert.equal(plan.incomingCueTime, 1.2);
  assert.equal(plan.incomingPlaybackRate, 1);
  assert.deepEqual(plan.policyReasons, ['outgoing-tempo']);
  assert.equal(plan.shouldStart, false);
});

test('two untrusted beat grids degrade to a plain crossfade', () => {
  const plan = planTransition({
    analysis: { bpm: 120, beatConfidence: 0.1, contentEndTime: 180, downbeats: [172, 174, 176, 178] },
    currentTime: 170,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: { bpm: 121, beatConfidence: 0.05, mixInTime: 16 },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionStyle, 'equal_power');
  assert.deepEqual(plan.policyReasons, ['beat-confidence']);
});

test('pending next-track analysis keeps the reliable standard fallback', () => {
  const plan = planTransition({
    analysis: { status: 'ready', trackId: 'current', bpm: 120, contentEndTime: 180 },
    currentTime: 160,
    currentTrack: { id: 'current', durationSeconds: 180 },
    duration: 180,
    fadeSeconds: 6,
    mode: 'smart',
    nextAnalysis: { status: 'loading', trackId: 'next' },
    nextTrack: { id: 'next', durationSeconds: 200 }
  });

  assert.equal(plan.transitionStyle, 'equal_power');
  assert.equal(plan.transitionStart, 174);
  assert.equal(plan.shouldStart, false);
  assert.equal(plan.reason, 'before-smart-analysis-fallback-window');
});

test('a missed early outro exit is replanned against the final content boundary', () => {
  const plan = planTransition({
    analysis: {
      bpm: 100,
      beatConfidence: 0.2,
      contentEndTime: 300,
      outroStartTime: 190,
      phraseBoundaries: [174, 190, 270, 286]
    },
    currentTime: 220,
    currentTrack: { id: 'current', durationSeconds: 305 },
    duration: 305,
    mode: 'smart',
    nextAnalysis: { bpm: 96, mixInTime: 18 },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionEnd, 300);
  assert.ok(plan.transitionStart > 220);
  assert.equal(plan.shouldStart, false);
});

test('low-confidence key guesses do not force a phrase switch', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 180,
      downbeats: [174, 176, 178],
      key: 'C major',
      keyConfidence: 0.02
    },
    currentTime: 170,
    currentTrack: { id: 'current', durationSeconds: 180 },
    duration: 180,
    mode: 'smart',
    nextAnalysis: {
      bpm: 120,
      beatConfidence: 0.9,
      mixInTime: 16,
      key: 'C major',
      keyConfidence: 0.03
    },
    nextTrack: { id: 'next', durationSeconds: 200 }
  });

  assert.equal(plan.transitionStyle, 'dj_blend');
});

test('vocal-on-vocal phrase switches use extended AutoMix blend', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 180,
      downbeats: [140, 142, 144, 146, 148, 150, 152, 154, 156, 158, 160, 162, 164, 166, 168, 170, 172, 174, 176, 178],
      key: 'A minor',
      vocalProbability: 0.8
    },
    currentTime: 140,
    currentTrack: { id: 'current', durationSeconds: 180 },
    duration: 180,
    mode: 'smart',
    nextAnalysis: {
      bpm: 120,
      beatConfidence: 0.9,
      key: 'A minor',
      mixInTime: 16,
      vocalProbability: 0.8
    },
    nextTrack: { id: 'next', durationSeconds: 200 }
  });

  assert.ok(plan.transitionBeats >= 8);
  assert.ok(plan.fadeSeconds >= 4);
  // The run-up is now the distance from the cue to the drop, not a delayed
  // handoff inside the overlap.
  assert.ok(plan.incomingHandoffTime > plan.incomingCueTime);
  assert.equal(plan.handoffStartSeconds, 0);
  assert.equal(plan.transitionStyle, 'dj_blend');
});

test('smart transitions prefer an interior silence-cliff mix-out', () => {
  const plan = planTransition({
    analysis: {
      bpm: 95,
      beatConfidence: 0.2,
      contentEndTime: 198,
      mixOutTime: 170,
      phraseBoundaries: [150, 162],
      energyCurve: silentAfter(198, 170)
    },
    currentTime: 165,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: { bpm: 90, audibleStartTime: 0.2 },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionEnd, 170);
  assert.ok(plan.transitionStart < plan.transitionEnd);
});

test('DJ transitions mix over the outro instead of stopping where it starts', () => {
  const plan = planTransition({
    analysis: {
      bpm: 100,
      beatConfidence: 0.2,
      contentEndTime: 200,
      mixOutTime: 200,
      // Twenty seconds of outro, all of it music. The outro is the part of a
      // track written to have something played over it, so the overlap sits
      // inside it and the transition still ends where the content does.
      outroStartTime: 180,
      phraseBoundaries: [168, 180],
      energyCurve: silentAfter(200, 200)
    },
    currentTime: 170,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: { bpm: 100, mixInTime: 19.2 },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionEnd, 200);
  assert.ok(plan.transitionStart >= 180,
    `overlap should sit inside the outro, started at ${plan.transitionStart}`);
  assert.equal(plan.incomingHandoffTime, 19.2);
  assertFadeClosesOnDrop(plan);
  assert.ok(plan.handoffDuration > 2);
  assert.ok(plan.fadeSeconds <= 12);
});

test('a long outro of real music is never skipped by the mix-out anchor', () => {
  // The native analyzer marks an outro up to 48 seconds before content end.
  // Anchoring there would cut the song short by most of a minute.
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 240,
      outroStartTime: 192,
      mixOutTime: 240,
      downbeats: Array.from({ length: 30 }, (_, index) => 180 + index * 2),
      energyCurve: silentAfter(240, 240),
      key: 'C major'
    },
    currentTime: 200,
    currentTrack: { id: 'current', durationSeconds: 245 },
    duration: 245,
    mode: 'smart',
    nextAnalysis: { bpm: 120, beatConfidence: 0.9, mixInTime: 16, key: 'C major' },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  // Ends at content end, give or take the downbeat it snaps to.
  assert.ok(plan.transitionEnd > 236, `transition ended at ${plan.transitionEnd}`);
  assert.ok(plan.transitionStart > 192,
    `expected the overlap inside the outro, started at ${plan.transitionStart}`);
});

test('interior mix-outs override the album-playthrough gapless shortcut', () => {
  const plan = planTransition({
    albumSequential: true,
    analysis: {
      bpm: 138.1833,
      beatConfidence: 0.2,
      contentEndTime: 264.75,
      mixOutTime: 188.15,
      energyCurve: silentAfter(264.75, 188.15)
    },
    currentTime: 188,
    currentTrack: { id: 'current', albumId: 'same-album', durationSeconds: 268 },
    duration: 268,
    mode: 'smart',
    nextAnalysis: { bpm: 69.8374, audibleStartTime: 0 },
    nextTrack: { id: 'next', albumId: 'same-album', durationSeconds: 136 }
  });

  assert.equal(plan.transitionEnd, 188.15);
  assert.notEqual(plan.transitionStyle, 'gapless');
  assert.equal(plan.shouldStart, true);
});

test('album-playthrough tracks without an interior mix-out remain gapless', () => {
  const plan = planTransition({
    albumSequential: true,
    analysis: { contentEndTime: 198, mixOutTime: 198 },
    currentTime: 199.6,
    currentTrack: { id: 'current', albumId: 'same-album', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextTrack: { id: 'next', albumId: 'same-album', durationSeconds: 180 }
  });

  assert.equal(plan.transitionStyle, 'gapless');
  assert.equal(plan.transitionEnd, 200);
  assert.equal(plan.shouldStart, true);
});

test('matching BPM tracks use quantized beat handoffs and bass swap without requiring key agreement', () => {
  const plan = planTransition({
    analysis: {
      bpm: 124,
      beatConfidence: 0.9,
      contentEndTime: 210,
      downbeats: [194, 198, 202, 206, 210],
      key: 'F♯ minor',
      keyConfidence: 0.05
    },
    currentTime: 190,
    currentTrack: { id: 'current', durationSeconds: 214 },
    duration: 214,
    mode: 'smart',
    nextAnalysis: {
      bpm: 124,
      beatConfidence: 0.9,
      audibleStartTime: 0,
      downbeats: [0, 4, 8, 12, 16],
      key: 'C major',
      keyConfidence: 0.05
    },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionStyle, 'dj_blend');
  assert.equal(plan.bassSwap, true);
  assert.ok(plan.fadeSeconds > 0);
  assert.ok(plan.handoffDuration > 0);
  assert.ok(Math.abs(plan.handoffStartSeconds % (60 / 124)) < 0.001);
});

test('same-album tracks outside an album playthrough are mixed, not handed off', () => {
  const plan = planTransition({
    analysis: { contentEndTime: 198, mixOutTime: 198 },
    currentTime: 199.6,
    currentTrack: { id: 'current', albumId: 'same-album', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextTrack: { id: 'next', albumId: 'same-album', durationSeconds: 180 }
  });

  assert.notEqual(plan.transitionStyle, 'gapless');
});
