import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const native = require('../native/build/Release/orchard_audio_analysis.node');

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

  assert.equal(result.analysisVersion, 6);
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
  assert.ok(result.vocalProbability >= 0 && result.vocalProbability <= 1);
  assert.ok(Math.abs(result.vocalProbability + result.instrumentalProbability - 1) < 0.001);
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
