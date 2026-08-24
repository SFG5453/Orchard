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
import { createRequire } from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const native = require('../native/build/Release/orchard_audio_analysis.node');
const { AUDIO_ANALYSIS_VERSION } = await import('../shared/audioAnalysis.js');

function syntheticTrack({ bpm = 120, duration = 48, sampleRate = 11025 } = {}) {
  const samples = new Float32Array(Math.floor(duration * sampleRate));
  const beatSeconds = 60 / bpm;
  const frequencies = [220, 261.63, 329.63];
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if (time < 1 || time > duration - 2) continue;
    const sectionGain = time < 9 || time > 40 ? 0.12 : 0.24;
    const chord = frequencies.reduce((sum, frequency) =>
      sum + Math.sin(2 * Math.PI * frequency * time), 0) / frequencies.length;
    const beatPhase = (time - 1) % beatSeconds;
    const click = beatPhase < 0.035
      ? Math.sin(2 * Math.PI * 1800 * time) * (1 - beatPhase / 0.035)
      : 0;
    samples[index] = sectionGain * chord + click * 0.7;
  }
  return { duration, sampleRate, samples };
}

test('native analyzer returns transition-ready musical features', async () => {
  const track = syntheticTrack();
  const result = await native.analyze(track.samples, track.sampleRate, track.duration);

  assert.equal(result.analysisVersion, AUDIO_ANALYSIS_VERSION);
  assert.ok(result.bpm >= 110 && result.bpm <= 130, `unexpected BPM: ${result.bpm}`);
  assert.ok(result.beatConfidence > 0);
  assert.ok(result.beats.length > 60);
  assert.ok(result.downbeats.length > 10);
  assert.ok(result.phraseBoundaries.length >= 3);
  assert.ok(result.mixInTime >= 10 && result.mixInTime <= 14, `unexpected mix-in: ${result.mixInTime}`);
  assert.ok(result.mixInConfidence > 0);
  assert.ok(Math.min(...result.downbeats.map((time) => Math.abs(time - result.mixInTime))) < 0.05);
  assert.ok(result.phrases.some((phrase) => phrase.type === 'intro'));
  assert.ok(
    result.phrases.some((phrase) => phrase.type === 'outro'),
    `missing outro phrase: ${JSON.stringify({ outroStartTime: result.outroStartTime, phrases: result.phrases })}`
  );
  assert.match(result.key, / (major|minor)$/);
  assert.equal(result.chroma.length, 12);
  assert.ok(
    result.audibleStartTime >= 0.25 && result.audibleStartTime <= 1.5,
    `unexpected audible start: ${result.audibleStartTime}`
  );
  assert.ok(result.contentEndTime >= 45 && result.contentEndTime <= 48);
  assert.ok(Number.isFinite(result.loudnessLufs));
  assert.ok(Number.isFinite(result.dynamicRangeDb));
  assert.ok(result.energyCurve.length > 20 && result.energyCurve.length <= 240);
  assert.ok(result.lowEnergyCurve.length > 0);
  assert.ok(result.midEnergyCurve.length > 0);
  assert.ok(result.highEnergyCurve.length > 0);
  assert.ok(result.vocalActivityMask.length > 0);
  assert.ok(result.mixInCandidates.length > 0);
  assert.ok(result.mixOutCandidates.length > 0);
  assert.ok(result.vocalProbability >= 0 && result.vocalProbability <= 1);
  assert.ok(Math.abs(result.vocalProbability + result.instrumentalProbability - 1) < 0.001);
  assert.deepEqual(result.meter, {
    beatsPerBar: 4,
    confidence: 0.15,
    source: 'assumed-4-4'
  });
  assert.ok(result.transitionFeatureFrames.length > 20);
  assert.ok(result.transitionFeatureFrames.length <= 240);
  assert.ok(result.transitionFeatureFrames.every((frame) =>
    Number.isFinite(frame.time) &&
    Number.isFinite(frame.energy) &&
    Number.isFinite(frame.novelty) &&
    Number.isFinite(frame.stability)
  ));
  assert.ok(
    result.structuralBoundaryCandidates.length >= 1,
    'the synthetic section changes should produce measured boundary evidence'
  );
  assert.ok(result.structuralBoundaryCandidates.every((boundary) =>
    boundary.source === 'detected-change' &&
    !('type' in boundary) &&
    Number.isFinite(boundary.observedTime) &&
    Number.isFinite(boundary.noveltyPeak) &&
    Number.isFinite(boundary.energyDelta)
  ));
});

test('native analyzer recognizes a voice-like harmonic signal as vocal', async () => {
  const duration = 16;
  const sampleRate = 11025;
  const samples = new Float32Array(duration * sampleRate);
  const formants = [500, 1500, 2500];
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    const fundamental = 115 + Math.sin(time * 0.7) * 8;
    const syllable = 0.35 + 0.65 * Math.max(0, Math.sin(Math.PI * 3.2 * time));
    let value = 0;
    for (let harmonic = 1; harmonic <= 28; harmonic += 1) {
      const frequency = fundamental * harmonic;
      const formantGain = formants.reduce((gain, formant) =>
        gain + Math.exp(-0.5 * ((frequency - formant) / 180) ** 2), 0);
      value += Math.sin(2 * Math.PI * frequency * time) * formantGain / harmonic;
    }
    samples[index] = value * syllable * 0.08;
  }

  const result = await native.analyze(samples, sampleRate, duration);
  assert.ok(
    result.vocalProbability >= 0.62,
    `unexpected vocal probability: ${result.vocalProbability}`
  );
});

test('low-energy curve measures bass rather than copying broadband loudness', async () => {
  const duration = 12;
  const sampleRate = 11025;
  const samples = new Float32Array(duration * sampleRate);
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if (time < 1 || time >= duration - 1) continue;
    // Keep amplitude steady while replacing a treble tone with a bass tone halfway through.
    // Broadband RMS should remain flat; only the real sub-250 Hz curve should move.
    const frequency = time < 6 ? 1000 : 80;
    samples[index] = Math.sin(2 * Math.PI * frequency * time) * 0.2;
  }

  const result = await native.analyze(samples, sampleRate, duration);
  const averageBetween = (curve, from, to) => {
    const points = curve.filter((point) => point.time >= from && point.time < to);
    return points.reduce((sum, point) => sum + point.energy, 0) / points.length;
  };
  const broadbandBefore = averageBetween(result.energyCurve, 2, 5);
  const broadbandAfter = averageBetween(result.energyCurve, 7, 10);
  const bassBefore = averageBetween(result.lowEnergyCurve, 2, 5);
  const bassAfter = averageBetween(result.lowEnergyCurve, 7, 10);

  assert.ok(
    Math.abs(broadbandAfter - broadbandBefore) < 0.15,
    `broadband envelope moved unexpectedly: ${broadbandBefore} -> ${broadbandAfter}`
  );
  assert.ok(
    bassAfter > bassBefore + 0.7,
    `bass curve did not detect the spectral handoff: ${bassBefore} -> ${bassAfter}`
  );
});

test('native analyzer detects short trailing silence', async () => {
  const duration = 8;
  const sampleRate = 11025;
  const samples = new Float32Array(duration * sampleRate);
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if (time >= duration - 0.55) continue;
    samples[index] = Math.sin(2 * Math.PI * 220 * time) * 0.2;
  }

  const result = await native.analyze(samples, sampleRate, duration);
  assert.ok(
    result.contentEndTime >= 7.25 && result.contentEndTime <= 7.75,
    `unexpected content end: ${result.contentEndTime}`
  );
});

test('native analyzer keeps a later ramp-up instead of mixing out mid-song', async () => {
  const duration = 60;
  const sampleRate = 11025;
  const samples = new Float32Array(duration * sampleRate);
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if (time >= 40 && time < 40.45) continue;
    samples[index] = Math.sin(2 * Math.PI * 220 * time) * 0.2;
  }

  const result = await native.analyze(samples, sampleRate, duration);
  assert.ok(result.mixOutTime >= duration - 0.1, `unexpected mix-out: ${result.mixOutTime}`);
  assert.equal(result.contentEndTime, duration);
});

test('native analyzer does not classify a quiet bridge before a final chorus as an outro', async () => {
  const duration = 100;
  const sampleRate = 11025;
  const samples = new Float32Array(duration * sampleRate);
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if ((time >= 60 && time < 74) || time >= 96) continue;
    samples[index] = Math.sin(2 * Math.PI * 220 * time) * 0.2;
  }

  const result = await native.analyze(samples, sampleRate, duration);
  assert.ok(result.outroStartTime >= 90, `unexpected outro: ${result.outroStartTime}`);
});

test('native analyzer keeps a moderately quieter outro as an early transition anchor', async () => {
  const duration = 100;
  const sampleRate = 11025;
  const samples = new Float32Array(duration * sampleRate);
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if (time >= 90) continue;
    const gain = time >= 60 && time < 70 ? 0.1 : 0.2;
    samples[index] = Math.sin(2 * Math.PI * 220 * time) * gain;
  }

  const result = await native.analyze(samples, sampleRate, duration);
  assert.ok(result.outroStartTime < 70, `unexpected late outro: ${result.outroStartTime}`);
});

test('native analyzer retains a useful late gap before resumed audio', async () => {
  const duration = 100;
  const sampleRate = 11025;
  const samples = new Float32Array(duration * sampleRate);
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if (time >= 84 && time < 88) continue;
    samples[index] = Math.sin(2 * Math.PI * 220 * time) * 0.2;
  }

  const result = await native.analyze(samples, sampleRate, duration);
  assert.ok(
    result.mixOutTime >= 83.9 && result.mixOutTime <= 84.1,
    `unexpected mix-out: ${result.mixOutTime}`
  );
});

// A percussive track of known tempo and phase, long enough that a grid which is
// extrapolated rather than tracked will have visibly drifted off it by the end.
function clickTrack({ bpm, duration, sampleRate = 11025, backbeat = false, firstBeat = 1 }) {
  const samples = new Float32Array(Math.floor(duration * sampleRate));
  const beatSeconds = 60 / bpm;
  const chord = [220, 261.63, 329.63];
  let noise = 12345;
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / sampleRate;
    if (time < firstBeat) continue;
    const tone = chord.reduce((sum, freq) => sum + Math.sin(2 * Math.PI * freq * time), 0) / 3;
    const phase = (time - firstBeat) % beatSeconds;
    const beatIndex = Math.floor((time - firstBeat) / beatSeconds) % 4;
    let hit = 0;
    if (phase < 0.04) {
      const envelope = 1 - phase / 0.04;
      // Kick on one and three, snare on two and four. The snare is deliberately
      // the loudest thing in the bar: picking the loudest onset finds it, and
      // that is a half-bar error.
      const kickGain = backbeat ? (beatIndex === 0 ? 1 : beatIndex === 2 ? 0.6 : 0) : 0.9;
      let snare = 0;
      if (backbeat && (beatIndex === 1 || beatIndex === 3)) {
        noise = (noise * 1103515245 + 12345) & 0x7fffffff;
        snare = ((noise / 0x7fffffff) * 2 - 1) * envelope * 1.1;
      }
      hit = Math.sin(2 * Math.PI * 55 * time) * envelope * kickGain + snare;
    }
    samples[index] = 0.2 * tone + hit * 0.7;
  }
  return samples;
}

test('the beat grid holds phase to the end of a long track', async () => {
  const bpm = 128;
  const duration = 300;
  const sampleRate = 11025;
  const firstBeat = 1;
  const result = await native.analyze(
    clickTrack({ bpm, duration, sampleRate, firstBeat }),
    sampleRate,
    duration
  );

  // The grid used to be a metronome extrapolated from a tempo estimate that is
  // quantized to about 0.15%, which is 90-300ms of accumulated phase error by
  // the time a track reaches the mix-out anchor in its outro -- most of a beat,
  // and enough on its own to stop two tracks ever lining up.
  const beatSeconds = 60 / bpm;
  const errorAt = (target) => {
    const beat = result.beats.reduce(
      (best, value) => (Math.abs(value - target) < Math.abs(best - target) ? value : best),
      result.beats[0]
    );
    const index = Math.round((beat - firstBeat) / beatSeconds);
    return Math.abs(beat - (firstBeat + index * beatSeconds));
  };

  assert.ok(Math.abs(result.bpm - bpm) < 0.05, `tempo drifted: ${result.bpm}`);
  for (const target of [30, 120, 250]) {
    assert.ok(errorAt(target) < 0.02, `grid is ${errorAt(target) * 1000}ms out at ${target}s`);
  }
});

test('downbeats land on the kick rather than the backbeat snare', async () => {
  const bpm = 120;
  const duration = 180;
  const sampleRate = 11025;
  const firstBeat = 1;
  const result = await native.analyze(
    clickTrack({ bpm, duration, sampleRate, backbeat: true, firstBeat }),
    sampleRate,
    duration
  );

  const beatSeconds = 60 / bpm;
  // Every downbeat must sit on beat one of the bar, not on two or four.
  const offsets = result.downbeats
    .filter((time) => time > firstBeat && time < duration - 5)
    .map((time) => ((Math.round((time - firstBeat) / beatSeconds) % 4) + 4) % 4);
  assert.ok(offsets.length > 10, `too few downbeats to judge: ${offsets.length}`);
  assert.ok(
    offsets.every((offset) => offset === 0),
    `downbeats landed off beat one: ${[...new Set(offsets)].join(',')}`
  );
});
