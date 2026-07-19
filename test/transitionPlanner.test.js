import assert from 'node:assert/strict';
import test from 'node:test';

import { planTransition } from '../src/audio/crossfade/transitionPlanner.js';

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
    currentTime: 174,
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
  assert.equal(plan.transitionStart, 173);
  assert.equal(plan.fadeSeconds, 7);
  assert.equal(plan.transitionBeats, 16);
  assert.ok(Math.abs(plan.handoffStartSeconds + plan.handoffDuration - plan.fadeSeconds) < 0.001);
  assert.equal(plan.shouldStart, true);
});

test('same-tempo phrase switches use an eight-beat transition', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 180,
      downbeats: [174, 176, 178],
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

  assert.equal(plan.transitionStyle, 'dj_switch');
  assert.equal(plan.transitionEnd, 178);
  assert.equal(plan.transitionStart, 174);
  assert.equal(plan.transitionBeats, 8);
  assert.equal(plan.handoffStartSeconds, 2);
  assert.equal(plan.handoffDuration, 2);
  assert.equal(plan.incomingCueTime, 2.2);
  assert.equal(plan.shouldStart, true);
});

test('DJ transitions prefer the analyzed interior mix-in downbeat', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 180,
      downbeats: [172, 174, 176, 178],
      key: 'C minor'
    },
    currentTime: 176,
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

  assert.equal(plan.incomingCueTime, 20.7);
  assert.equal(plan.transitionBeats, 8);
});

test('filtered DJ transitions pre-roll an intro into its analyzed handoff', () => {
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

  const dominanceTime = plan.handoffStartSeconds + plan.handoffDuration * 0.58;
  assert.equal(plan.transitionStyle, 'dj_blend');
  assert.equal(plan.incomingCueTime, 0);
  assert.equal(plan.incomingHandoffTime, 22.9108);
  assert.ok(Math.abs(plan.handoffDuration - (16 * 60 / 91.7354)) < 0.001);
  assert.ok(Math.abs(dominanceTime - plan.incomingHandoffTime) < 0.001);
  assert.ok(plan.fadeSeconds >= 26 && plan.fadeSeconds <= 30);
  assert.equal(plan.transitionEnd, 258.4);
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

test('vocal-on-vocal phrase switches leave a sixteen-beat bed', () => {
  const plan = planTransition({
    analysis: {
      bpm: 120,
      beatConfidence: 0.9,
      contentEndTime: 180,
      downbeats: [170, 172, 174, 176, 178],
      key: 'A minor',
      vocalProbability: 0.8
    },
    currentTime: 172,
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

  assert.equal(plan.transitionBeats, 16);
  assert.equal(plan.fadeSeconds, 8);
  assert.equal(plan.handoffStartSeconds, 4);
});

test('smart transitions prefer an interior silence-cliff mix-out', () => {
  const plan = planTransition({
    analysis: {
      bpm: 95,
      beatConfidence: 0.2,
      contentEndTime: 198,
      mixOutTime: 170,
      phraseBoundaries: [150, 162]
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

test('DJ transitions finish at an analyzed outro boundary', () => {
  const plan = planTransition({
    analysis: {
      bpm: 100,
      beatConfidence: 0.2,
      contentEndTime: 200,
      mixOutTime: 200,
      outroStartTime: 180,
      phraseBoundaries: [168, 180]
    },
    currentTime: 170,
    currentTrack: { id: 'current', durationSeconds: 200 },
    duration: 200,
    mode: 'smart',
    nextAnalysis: { bpm: 100, mixInTime: 19.2 },
    nextTrack: { id: 'next', durationSeconds: 220 }
  });

  assert.equal(plan.transitionEnd, 180);
  assert.equal(plan.incomingCueTime, 0);
  assert.equal(plan.incomingHandoffTime, 19.2);
  assert.ok(Math.abs(
    plan.handoffStartSeconds + plan.handoffDuration * 0.58 - plan.incomingHandoffTime
  ) < 0.001);
  assert.ok(plan.transitionStart < 180);
});

test('interior mix-outs override the same-album gapless shortcut', () => {
  const plan = planTransition({
    analysis: {
      bpm: 138.1833,
      beatConfidence: 0.2,
      contentEndTime: 264.75,
      mixOutTime: 188.15
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

test('same-album tracks without an interior mix-out remain gapless', () => {
  const plan = planTransition({
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
