import assert from 'node:assert/strict';
import test from 'node:test';

import { mergeBpmMetadata } from '../src/audio/crossfade/bpmMetadata.js';
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
  assert.ok(plan.transitionBeats >= 8);
  assert.ok(plan.fadeSeconds >= 4);
  assert.equal(plan.bassSwap, true);
  assert.ok(Math.abs(plan.incomingCueTime - 0.2) < 0.000001);
  assert.ok(Math.abs(
    plan.incomingCueTime + plan.handoffStartSeconds * plan.incomingPlaybackRate -
      plan.incomingHandoffTime
  ) < 0.001);
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

  assert.ok(Math.abs(
    plan.incomingCueTime + plan.handoffStartSeconds * plan.incomingPlaybackRate -
      plan.incomingHandoffTime
  ) < 0.001);
  assert.ok(plan.transitionBeats >= 8);
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

  assert.equal(plan.transitionStyle, 'dj_blend');
  assert.ok(Math.abs(plan.incomingCueTime - 0.11730915568035627) < 0.000001);
  assert.equal(plan.incomingHandoffTime, 22.9108);
  assert.ok(Math.abs(
    plan.incomingCueTime + plan.handoffStartSeconds * plan.incomingPlaybackRate -
      plan.incomingHandoffTime
  ) < 0.001);
  assert.ok(plan.handoffDuration > 4);
  assert.ok(plan.fadeSeconds >= 20 && plan.fadeSeconds <= 40);
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

  assert.ok(plan.transitionBeats >= 16);
  assert.ok(plan.fadeSeconds >= 8);
  assert.ok(plan.handoffStartSeconds > 0);
  assert.equal(plan.transitionStyle, 'dj_blend');
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
  assert.ok(plan.handoffDuration > 4);
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
