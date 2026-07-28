import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const native = require('../native/build/Release/orchard_audio_analysis.node');

const SAMPLE_RATE = 44100;

function sine({ frequency = 440, duration = 2, amplitude = 0.5, phase = 0 } = {}) {
  const samples = new Float32Array(Math.floor(duration * SAMPLE_RATE));
  for (let index = 0; index < samples.length; index += 1) {
    samples[index] = amplitude * Math.sin(2 * Math.PI * frequency * (index / SAMPLE_RATE) + phase);
  }
  return samples;
}

// Dominant frequency by parabolic-interpolated peak of a naive DFT restricted
// to a musical band, which is enough to catch a pitch shift of a few percent.
// The band is deliberately wide enough to contain where a resampling
// implementation would land 440 Hz (415 Hz at 0.94, 466 Hz at 1.06).
function dominantFrequency(samples, low = 380, high = 500) {
  const length = Math.min(samples.length, SAMPLE_RATE * 0.5);
  let best = { frequency: 0, magnitude: -1 };
  for (let frequency = low; frequency <= high; frequency += 1) {
    let real = 0;
    let imaginary = 0;
    for (let index = 0; index < length; index += 1) {
      const angle = 2 * Math.PI * frequency * (index / SAMPLE_RATE);
      real += samples[index] * Math.cos(angle);
      imaginary -= samples[index] * Math.sin(angle);
    }
    const magnitude = Math.hypot(real, imaginary);
    if (magnitude > best.magnitude) best = { frequency, magnitude };
  }
  return best.frequency;
}

function rms(samples) {
  let total = 0;
  for (let index = 0; index < samples.length; index += 1) total += samples[index] ** 2;
  return Math.sqrt(total / Math.max(1, samples.length));
}

test('time stretch scales duration by the requested ratio', async () => {
  const input = sine({ duration: 2 });
  for (const ratio of [0.94, 1, 1.06]) {
    const [output] = await native.timeStretch([input], SAMPLE_RATE, ratio);
    const expected = input.length * ratio;
    // One synthesis frame of slack: the final frame only lands if a whole
    // window still fits inside the input.
    assert.ok(
      Math.abs(output.length - expected) < SAMPLE_RATE * 0.06,
      `ratio ${ratio} produced ${output.length}, expected near ${expected}`
    );
  }
});

test('time stretch preserves pitch', async () => {
  const input = sine({ frequency: 440, duration: 2 });
  const [stretched] = await native.timeStretch([input], SAMPLE_RATE, 1.06);
  const [compressed] = await native.timeStretch([input], SAMPLE_RATE, 0.94);

  // Resampling instead of time-stretching would move 440 Hz to 415 / 468 Hz,
  // far outside this tolerance.
  assert.ok(
    Math.abs(dominantFrequency(stretched) - 440) < 6,
    `stretched pitch drifted to ${dominantFrequency(stretched)}`
  );
  assert.ok(
    Math.abs(dominantFrequency(compressed) - 440) < 6,
    `compressed pitch drifted to ${dominantFrequency(compressed)}`
  );
});

test('time stretch holds level steady rather than amplitude-modulating', async () => {
  const input = sine({ duration: 2 });
  const [output] = await native.timeStretch([input], SAMPLE_RATE, 1.05);
  const trimmed = output.subarray(SAMPLE_RATE * 0.2, output.length - SAMPLE_RATE * 0.2);

  // Overlap-add without correct window normalization shows up as a periodic
  // level ripple, so compare the quietest window against the loudest.
  const windows = [];
  const size = Math.floor(SAMPLE_RATE * 0.05);
  for (let start = 0; start + size < trimmed.length; start += size) {
    windows.push(rms(trimmed.subarray(start, start + size)));
  }
  const quietest = Math.min(...windows);
  const loudest = Math.max(...windows);
  assert.ok(
    quietest / loudest > 0.8,
    `level ripple across the stretch: ${quietest.toFixed(4)} vs ${loudest.toFixed(4)}`
  );
});

test('time stretch keeps stereo channels aligned', async () => {
  // Opposite phase on each channel: a per-channel similarity search would pick
  // different offsets and partially cancel when summed to mono.
  const left = sine({ duration: 2, phase: 0 });
  const right = sine({ duration: 2, phase: Math.PI });
  const [outLeft, outRight] = await native.timeStretch([left, right], SAMPLE_RATE, 1.05);

  assert.equal(outLeft.length, outRight.length);
  const length = Math.min(outLeft.length, outRight.length);
  let worst = 0;
  for (let index = SAMPLE_RATE; index < length - SAMPLE_RATE; index += 1) {
    worst = Math.max(worst, Math.abs(outLeft[index] + outRight[index]));
  }
  assert.ok(worst < 0.05, `channels drifted apart, residual sum ${worst.toFixed(4)}`);
});

test('phase-cancelling stereo does not turn correlation noise into pitch flutter', async () => {
  const left = sine({ duration: 4 });
  const right = new Float32Array(left.length);
  let randomState = 123456789;
  for (let index = 0; index < right.length; index += 1) {
    randomState = (Math.imul(1664525, randomState) + 1013904223) >>> 0;
    const noise = (randomState / 0x100000000 * 2 - 1) * 0.00001;
    right[index] = -left[index] + noise;
  }

  const [output] = await native.timeStretch([left, right], SAMPLE_RATE, 1.05);
  const window = Math.floor(SAMPLE_RATE * 0.2);
  const frequencies = [];
  for (let start = Math.floor(SAMPLE_RATE * 0.5);
    start + window < output.length - SAMPLE_RATE * 0.5;
    start += window) {
    let positiveCrossings = 0;
    for (let index = start + 1; index < start + window; index += 1) {
      if (output[index - 1] <= 0 && output[index] > 0) positiveCrossings += 1;
    }
    frequencies.push(positiveCrossings / (window / SAMPLE_RATE));
  }
  const lowest = Math.min(...frequencies);
  const highest = Math.max(...frequencies);
  const average = frequencies.reduce((total, value) => total + value, 0) / frequencies.length;
  assert.ok(Math.abs(average - 440) < 6, `phase-cancelled guide drifted to ${average} Hz`);
  assert.ok(
    highest - lowest < 2,
    `phase-cancelled guide fluttered between ${lowest} and ${highest} Hz`
  );
});

test('time stretch rejects malformed input', async () => {
  const input = sine({ duration: 0.5 });
  assert.throws(() => native.timeStretch([], SAMPLE_RATE, 1), /valid/);
  assert.throws(() => native.timeStretch([input], SAMPLE_RATE, 0), /valid/);
  assert.throws(() => native.timeStretch([input], 10, 1), /valid/);
  assert.throws(() => native.timeStretch([input, sine({ duration: 0.4 })], SAMPLE_RATE, 1), /same length/);
  assert.throws(() => native.timeStretch([[1, 2, 3]], SAMPLE_RATE, 1), /Float32Array/);
});

test('glides onto the target ratio instead of stepping onto it', async () => {
  const input = sine({ duration: 4 });
  const [stepped] = await native.timeStretch([input], SAMPLE_RATE, 1.06);
  const [glided] = await native.timeStretch([input], SAMPLE_RATE, 1.06, {
    startRatio: 1,
    glide: 0.5
  });

  // Half the input is spent easing from 1.0 up to 1.06, so the glided render
  // is shorter than one that holds 1.06 throughout, but still longer than the
  // untouched input.
  assert.ok(
    glided.length < stepped.length,
    `glide did not shorten the render: ${glided.length} vs ${stepped.length}`
  );
  assert.ok(
    glided.length > input.length,
    `glide did not stretch at all: ${glided.length} vs ${input.length}`
  );
});

test('binding publishes the transparent ratio limit', () => {
  assert.ok(native.maxTransparentRatioDeviation > 0.01);
  assert.ok(native.maxTransparentRatioDeviation <= 0.2);
});
