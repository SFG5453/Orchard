import assert from 'node:assert/strict';
import test from 'node:test';

import {
  padToFixedFrames,
  reduceMaskToBandCurve,
  resetVocalMask,
  trackVocalMask
} from '../electron/audio/vocalMaskTracker.js';

const CHANNELS = 2;
const BINS = 2049;

test('padding zero-fills the tail without disturbing real frames', () => {
  const frames = 5;
  const fixed = 8;
  const values = new Float32Array(CHANNELS * BINS * frames);
  // Mark one distinctive value per channel so a stride mistake is visible.
  values[(0 * BINS + 10) * frames + 2] = 0.7;
  values[(1 * BINS + 20) * frames + 4] = 0.9;

  const padded = padToFixedFrames(values, CHANNELS, BINS, frames, fixed);
  assert.equal(padded.length, CHANNELS * BINS * fixed);
  // Float32 round-trip, not exact decimal equality.
  assert.ok(Math.abs(padded[(0 * BINS + 10) * fixed + 2] - 0.7) < 1e-6);
  assert.ok(Math.abs(padded[(1 * BINS + 20) * fixed + 4] - 0.9) < 1e-6);
  // Anything at or past the real frame count must be exactly zero.
  for (let frame = frames; frame < fixed; frame += 1) {
    assert.equal(padded[(0 * BINS + 10) * fixed + frame], 0);
  }
});

test('padding truncates rather than overrunning a spectrogram longer than the budget', () => {
  const frames = 10;
  const fixed = 4;
  const values = new Float32Array(CHANNELS * BINS * frames);
  values[(0 * BINS + 5) * frames + 9] = 1;
  const padded = padToFixedFrames(values, CHANNELS, BINS, frames, fixed);
  assert.equal(padded.length, CHANNELS * BINS * fixed);
  // The frame beyond the fixed budget must simply be dropped, not wrapped or read.
  assert.ok(padded.every((value) => value === 0));
});

test('the band curve averages the mask over the requested frequency range only', () => {
  const strideFrames = 4;
  const usableFrames = 3;
  const bins = 10;
  const channels = 1;
  const sampleRate = 100;
  const fftSize = 20; // bin width = 5 Hz/bin
  const mix = new Float32Array(channels * bins * strideFrames).fill(1);
  const target = new Float32Array(channels * bins * strideFrames).fill(0);
  // lowHz=10 -> bin 2, highHz=20 -> bin 4: only bins 2-4 should count.
  for (const bin of [2, 3, 4]) {
    for (let frame = 0; frame < usableFrames; frame += 1) {
      target[bin * strideFrames + frame] = 0.8;
    }
  }
  // A bin outside the range with a very different value must not leak in.
  target[8 * strideFrames + 0] = 0.01;

  const curve = reduceMaskToBandCurve(mix, target, {
    channels, bins, strideFrames, usableFrames,
    sampleRate, fftSize, lowHz: 10, highHz: 20
  });
  assert.equal(curve.length, usableFrames);
  for (const value of curve) assert.ok(Math.abs(value - 0.8) < 1e-6, `expected 0.8, got ${value}`);
});

test('the band curve ignores padded (silent) frames beyond usableFrames', () => {
  const strideFrames = 6;
  const usableFrames = 2;
  const bins = 6;
  const channels = 1;
  const mix = new Float32Array(channels * bins * strideFrames).fill(1);
  const target = new Float32Array(channels * bins * strideFrames).fill(0.5);
  // If the padded tail leaked in, this would corrupt every frame's average --
  // but it is stored past usableFrames and must never be read.
  target[3 * strideFrames + 5] = 999;
  mix[3 * strideFrames + 5] = 0.0001;

  const curve = reduceMaskToBandCurve(mix, target, {
    channels, bins, strideFrames, usableFrames,
    sampleRate: 100, fftSize: 12, lowHz: 0, highHz: 50
  });
  assert.equal(curve.length, usableFrames);
  for (const value of curve) assert.ok(Math.abs(value - 0.5) < 1e-9);
});

function fakeSpectrogram(frames) {
  return {
    frames,
    channels: CHANNELS,
    bins: BINS,
    framesPerSecond: 44100 / 1024,
    values: new Float32Array(CHANNELS * BINS * frames).fill(0.3)
  };
}

test('trackVocalMask refuses a spectrogram longer than the fixed model budget', async () => {
  const result = await trackVocalMask(fakeSpectrogram(1200), {
    load: async () => { throw new Error('must not be reached'); }
  });
  assert.equal(result, null);
});

test('trackVocalMask refuses a spectrogram with the wrong channel or bin count', async () => {
  const wrongChannels = { ...fakeSpectrogram(100), channels: 1 };
  const wrongBins = { ...fakeSpectrogram(100), bins: 512 };
  const load = async () => { throw new Error('must not be reached'); };
  assert.equal(await trackVocalMask(wrongChannels, { load }), null);
  assert.equal(await trackVocalMask(wrongBins, { load }), null);
});

test('trackVocalMask runs the whole chain against a stubbed session', async () => {
  const frames = 50;
  const fakeRuntime = {
    Tensor: class {
      constructor(kind, data, dims) {
        this.data = data;
        this.dims = dims;
      }
    },
    InferenceSession: {
      create: async () => ({
        run: async (feeds) => {
          const dims = feeds.mix_magnitude.dims;
          const size = dims.reduce((a, b) => a * b, 1);
          // Half the input magnitude back: a flat 0.5 mask everywhere.
          const data = new Float32Array(size);
          for (let index = 0; index < size; index += 1) {
            data[index] = feeds.mix_magnitude.data[index] * 0.5;
          }
          return { target_magnitude: { data } };
        }
      })
    }
  };
  const result = await trackVocalMask(fakeSpectrogram(frames), {
    modelPath: 'models/vocal-separation/vocals_umxhq_int8.onnx',
    load: async () => fakeRuntime
  });
  assert.ok(result);
  assert.equal(result.curve.length, frames);
  for (const value of result.curve) assert.ok(Math.abs(value - 0.5) < 1e-6, `expected 0.5, got ${value}`);
});

test('a session that fails to load resolves null rather than throwing', async () => {
  // Without this, the module-level session cache from the previous test's
  // successful (stubbed) load would still be warm, and this call would reuse
  // it instead of actually exercising the missing-model path.
  resetVocalMask();
  const result = await trackVocalMask(fakeSpectrogram(50), {
    modelPath: '/nonexistent/model.onnx'
  });
  assert.equal(result, null);
  resetVocalMask();
});
