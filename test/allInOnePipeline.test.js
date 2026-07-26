import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createAllInOneFilterbank,
  extractAllInOneMixSpectrogram,
  resampleAudioChannels
} from '../electron/audio/allInOnePipeline.js';
import { combineEdgeAnalyses } from '../electron/audio/onnxSmartCrossfade.js';

test('All-In-One preprocessing produces the model contract of 81 frequency bands', () => {
  const filters = createAllInOneFilterbank();
  assert.equal(filters.length, 81);
  assert.ok(filters.every((filter) =>
    filter.length > 0 &&
    filter.every((entry) => Number.isInteger(entry.bin) && Number.isFinite(entry.value))
  ));
});

test('audio resampling preserves channels and expected duration', () => {
  const source = [
    Float32Array.from({ length: 48_000 }, (_, index) => Math.sin(index / 20)),
    Float32Array.from({ length: 48_000 }, (_, index) => Math.cos(index / 20))
  ];
  const output = resampleAudioChannels(source, 48_000, 16_000);
  assert.equal(output.length, 2);
  assert.equal(output[0].length, 16_000);
  assert.equal(output[1].length, 16_000);
});

test('mix-native All-In-One preprocessing computes one plane and repeats the model contract', () => {
  const samples = Float32Array.from(
    { length: 4_410 },
    (_, index) => Math.sin(index * 2 * Math.PI * 220 / 44_100)
  );
  const spectrogram = extractAllInOneMixSpectrogram({
    channels: [samples, samples],
    sampleRate: 44_100
  });
  const planeLength = spectrogram.frames * spectrogram.bands;

  assert.equal(spectrogram.bands, 81);
  assert.equal(spectrogram.data.length, planeLength * 4);
  assert.deepEqual(
    spectrogram.data.slice(planeLength, planeLength * 2),
    spectrogram.data.slice(0, planeLength)
  );
});

test('edge analyses are remapped onto the original song timeline', () => {
  const combined = combineEdgeAnalyses({
    duration: 120,
    headDuration: 32,
    tailDuration: 40,
    head: {
      aiBeatActivationConfidence: 0.61,
      aiDownbeatActivationConfidence: 0.58,
      aiStructureConfidence: 0.5,
      phrases: [{ start: 0, end: 16, type: 'intro', confidence: 0.8 }]
    },
    tail: {
      aiBeatActivationConfidence: 0.7,
      aiDownbeatActivationConfidence: 0.54,
      aiStructureConfidence: 0.6,
      phrases: [
        { start: 0, end: 24, type: 'chorus', confidence: 0.7 },
        { start: 24, end: 40, type: 'break', confidence: 0.75 }
      ],
      mixOutTime: 40
    }
  });

  assert.equal(combined.aiAnalysisScope, 'head-tail');
  assert.equal(combined.aiBeatActivationConfidence, 0.7);
  assert.equal(combined.aiDownbeatActivationConfidence, 0.58);
  assert.deepEqual(combined.phrases.map(({ start, end, type }) => ({ start, end, type })), [
    { start: 0, end: 16, type: 'intro' },
    { start: 80, end: 104, type: 'chorus' },
    { start: 104, end: 120, type: 'break' }
  ]);
  assert.equal(combined.mixOutTime, 120);
});
