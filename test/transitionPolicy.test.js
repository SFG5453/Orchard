import assert from 'node:assert/strict';
import test from 'node:test';

import {
  assessTransitionTier,
  audibleSecondsBetween,
  isVocalClash,
  rankMixInCandidates,
  resolveMixOutAnchor,
  vocalActivityBetween
} from '../src/audio/crossfade/transitionPolicy.js';

function energyCurve(duration, silentFrom = Infinity) {
  const curve = [];
  for (let time = 0; time < duration; time += 1) {
    curve.push({ time, energy: time >= silentFrom ? 0 : 0.8 });
  }
  return curve;
}

function maskedAnalysis(values, secondsPerSample = 1) {
  return {
    energyCurve: values.map((_, index) => ({ time: index * secondsPerSample, energy: 0.8 })),
    vocalActivityMask: values
  };
}

test('trusted grids within the stretch window rate a beat-matched transition', () => {
  const policy = assessTransitionTier({
    analysis: { bpm: 126, beatConfidence: 0.9 },
    nextAnalysis: { bpm: 124, beatConfidence: 0.7 }
  });
  assert.equal(policy.tier, 'beatmatched');
  assert.deepEqual(policy.reasons, []);
  assert.equal(policy.beatConfidence, 0.7);
});

test('octave-distant tempi are counted on the shared grid before judging', () => {
  const policy = assessTransitionTier({
    analysis: { bpm: 126, beatConfidence: 0.9 },
    nextAnalysis: { bpm: 63, beatConfidence: 0.9 }
  });
  assert.equal(policy.tier, 'beatmatched');
});

test('tempo distance demotes to the DJ-assisted tier', () => {
  const policy = assessTransitionTier({
    analysis: { bpm: 126, beatConfidence: 0.9 },
    nextAnalysis: { bpm: 100, beatConfidence: 0.9 }
  });
  assert.equal(policy.tier, 'dj_assisted');
  assert.equal(policy.reasons[0], 'tempo-distance');
});

test('one weak beat grid demotes to the DJ-assisted tier', () => {
  const policy = assessTransitionTier({
    analysis: { bpm: 126, beatConfidence: 0.9 },
    nextAnalysis: { bpm: 126, beatConfidence: 0.3 }
  });
  assert.equal(policy.tier, 'dj_assisted');
  assert.deepEqual(policy.reasons, ['beat-confidence']);
});

test('missing tempo or two untrusted grids bottom out at a plain crossfade', () => {
  assert.equal(
    assessTransitionTier({
      analysis: { bpm: 0, beatConfidence: 0.9 },
      nextAnalysis: { bpm: 126, beatConfidence: 0.9 }
    }).tier,
    'plain_crossfade'
  );
  const catalogOnly = assessTransitionTier({
    analysis: { bpm: 120, beatConfidence: 0 },
    nextAnalysis: { bpm: 120, beatConfidence: 0.1 }
  });
  assert.equal(catalogOnly.tier, 'plain_crossfade');
  assert.deepEqual(catalogOnly.reasons, ['beat-confidence']);
});

test('audible seconds count music and ignore silence', () => {
  const analysis = { energyCurve: energyCurve(100, 60) };
  assert.ok(Math.abs(audibleSecondsBetween(analysis, 0, 59) - 60) < 1.5);
  assert.equal(audibleSecondsBetween(analysis, 60, 99), 0);
  assert.equal(audibleSecondsBetween({}, 0, 10), null);
});

test('an outro marker never anchors a transition ahead of real music', () => {
  // The native analyzer marks an outro up to 48s before content end.
  const anchor = resolveMixOutAnchor({
    contentEndTime: 240,
    outroStartTime: 192,
    energyCurve: energyCurve(240)
  });
  assert.equal(anchor.type, 'content_end');
  assert.equal(anchor.time, 240);
});

test('a genuine silence cliff still anchors the transition', () => {
  const anchor = resolveMixOutAnchor({
    contentEndTime: 264,
    mixOutTime: 188,
    energyCurve: energyCurve(264, 188)
  });
  assert.equal(anchor.type, 'interior_mix_out');
  assert.equal(anchor.time, 188);
  assert.equal(anchor.discardedMusicSeconds, 0);
});

test('a silence cliff followed by more music is not a mix-out', () => {
  // A four-second gap, then the final chorus. Skipping here would cut the
  // song short even though the analyzer scored the boundary highly.
  const curve = energyCurve(264).map((point) => (
    point.time >= 188 && point.time < 192 ? { ...point, energy: 0 } : point
  ));
  const anchor = resolveMixOutAnchor({
    contentEndTime: 264,
    mixOutTime: 188,
    energyCurve: curve
  });
  assert.equal(anchor.type, 'content_end');
});

test('a short unplayed tail stays within the budget', () => {
  const anchor = resolveMixOutAnchor({
    contentEndTime: 200,
    mixOutTime: 192,
    energyCurve: energyCurve(200)
  });
  assert.equal(anchor.type, 'interior_mix_out');
  assert.ok(anchor.discardedMusicSeconds <= 12);
});

test('without an energy curve the budget errs toward playing the track', () => {
  assert.equal(resolveMixOutAnchor({ contentEndTime: 240, mixOutTime: 180 }).type, 'content_end');
  assert.equal(resolveMixOutAnchor({ contentEndTime: 240, mixOutTime: 232 }).type, 'interior_mix_out');
});

test('vocal activity averages only the samples inside the window', () => {
  const analysis = maskedAnalysis([0.9, 0.9, 0.9, 0.1, 0.1, 0.1]);
  assert.equal(vocalActivityBetween(analysis, 0, 2), 0.9);
  assert.ok(Math.abs(vocalActivityBetween(analysis, 3, 5) - 0.1) < 1e-9);
  assert.equal(vocalActivityBetween(analysis, 10, 20), null);
  assert.equal(vocalActivityBetween({}, 0, 10), null);
  assert.equal(vocalActivityBetween(analysis, 5, 5), null);
});

test('a vocal clash needs measurable singing on both sides', () => {
  assert.equal(isVocalClash(0.9, 0.8), true);
  assert.equal(isVocalClash(0.9, 0.2), false);
  // The JS fallback analyzer's flat 0.5 mask must never trip vocal logic.
  assert.equal(isVocalClash(0.5, 0.5), false);
  assert.equal(isVocalClash(null, 0.9), false);
});

test('an analyzed drop outranks a higher-scored phrase boundary', () => {
  const ranked = rankMixInCandidates({
    bpm: 126,
    mixInCandidates: [
      { time: 45, score: 0.4, type: 'phrase' },
      { time: 30.476, score: 0.2, type: 'main_drop' }
    ]
  });
  assert.equal(ranked[0].time, 30.476);
});

test('a vocal run-up demotes an otherwise equal entry point', () => {
  const beat = 60 / 126;
  const mask = [];
  const curve = [];
  for (let time = 0; time < 80; time += 1) {
    curve.push({ time, energy: 0.8 });
    // Singing leads into the first candidate; the second's run-up is clear.
    mask.push(time < 32 ? 0.9 : 0.1);
  }
  const ranked = rankMixInCandidates({
    bpm: 126,
    energyCurve: curve,
    vocalActivityMask: mask,
    mixInCandidates: [
      { time: 32 * beat * 2, score: 0.9, type: 'intro_drop' },
      { time: 64 * beat * 2, score: 0.9, type: 'intro_drop' }
    ]
  });
  assert.equal(ranked[0].time, 64 * beat * 2);
});

test('a cold open with no run-up is penalized', () => {
  const ranked = rankMixInCandidates({
    bpm: 120,
    audibleStartTime: 0,
    mixInCandidates: [
      { time: 0.5, score: 0.8, type: 'pickup' },
      { time: 16, score: 0.8, type: 'pickup' }
    ]
  });
  assert.equal(ranked[0].time, 16);
});
