import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createAllInOneFilterbank,
  resampleAudioChannels
} from '../electron/audio/allInOnePipeline.js';

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
