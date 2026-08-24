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
import test from 'node:test';

import {
  BEAT_WINDOW_SECONDS,
  aggregateLogits,
  gridConfidence,
  pickPeaks,
  refineBeatsWithModel,
  splitSpectrogram,
  tempoFromBeats,
  trackBeats
} from '../electron/audio/beatThisTracker.js';

const MELS = 128;

function flatSpectrogram(frames, value = 1) {
  return new Float32Array(frames * MELS).fill(value);
}

test('the beat window costs exactly one model chunk', () => {
  // 1500-frame chunks minus a 6-frame border each side at 50 fps.
  assert.equal(BEAT_WINDOW_SECONDS, (1500 - 12) / 50);
  // The spectrogram of a window is one frame longer than the chunk interior
  // (centre padding rounds up). That single frame must not buy a second full
  // inference -- it did, and it doubled the model cost of every analysis.
  for (const frames of [1488, 1489]) {
    const chunks = splitSpectrogram(flatSpectrogram(frames), frames, MELS);
    assert.equal(chunks.length, 1, `${frames} frames split into ${chunks.length} chunks`);
    assert.equal(chunks[0].frames, 1500);
  }
});

test('splitting pads the borders rather than truncating the audio', () => {
  const frames = 4000;
  const chunks = splitSpectrogram(flatSpectrogram(frames), frames, MELS);
  // Every model frame must be covered by some chunk's interior.
  assert.ok(chunks.length >= 3);
  assert.equal(chunks[0].start, -6);
  // The first chunk is zero-padded on the left where start is negative.
  const firstRow = chunks[0].values.subarray(0, MELS);
  assert.ok(firstRow.every((value) => value === 0));
  // The final chunk is pulled back so it is full-length rather than a stub.
  const last = chunks[chunks.length - 1];
  assert.equal(last.start, frames - 1494);
});

test('aggregation keeps the first prediction where chunks overlap', () => {
  const frames = 20;
  const chunks = [
    { start: 0, beatLogits: new Float32Array(frames).fill(1), downbeatLogits: new Float32Array(frames).fill(1) },
    { start: 0, beatLogits: new Float32Array(frames).fill(2), downbeatLogits: new Float32Array(frames).fill(2) }
  ];
  const { beat } = aggregateLogits(chunks, frames);
  // Interior frames come from the first chunk even though the second also
  // covers them; borders (6 frames each side) are discarded from both.
  assert.equal(beat[10], 1);
});

test('peak picking is sub-frame and ignores non-maxima', () => {
  const logits = new Float32Array(50).fill(-5);
  // An asymmetric peak: the true maximum sits between frames 20 and 21.
  logits[19] = 1.0;
  logits[20] = 3.0;
  logits[21] = 2.5;
  const peaks = pickPeaks(logits);
  assert.equal(peaks.length, 1);
  assert.ok(peaks[0] > 20 && peaks[0] < 21, `expected a sub-frame peak, got ${peaks[0]}`);
});

test('tempo from beats survives a missed beat', () => {
  const interval = 60 / 128;
  const beats = [];
  for (let index = 0; index < 64; index += 1) beats.push(index * interval);
  // Remove one beat: the gap doubles, and a mean would report a slower tempo.
  beats.splice(30, 1);
  const bpm = tempoFromBeats(beats);
  assert.ok(Math.abs(bpm - 128) < 0.5, `expected 128, got ${bpm}`);
});

test('grid confidence rewards regularity and decisive peaks', () => {
  const interval = 60 / 128;
  const regular = Array.from({ length: 64 }, (_, index) => index * interval);
  const strong = gridConfidence(regular, regular.map(() => 3));
  const ragged = regular.map((time, index) => time + (index % 2 ? 0.12 : 0));
  const weak = gridConfidence(ragged, ragged.map(() => 0));
  assert.ok(strong > 0.85, `strong grid scored ${strong}`);
  assert.ok(weak < strong - 0.2, `ragged grid scored ${weak} against ${strong}`);
});

// The merge policy is where the aubio lessons are encoded, so each rule gets
// pinned: agreement fixes downbeats, metrical ambiguity is no opinion, and
// phase disagreement demotes.

function nativeResult({ bpm = 128, beats = 512, offset = 1 } = {}) {
  const interval = 60 / bpm;
  return {
    bpm,
    beatConfidence: 0.5,
    beats: Array.from({ length: beats }, (_, index) => offset + index * interval),
    downbeats: []
  };
}

function stubDeps(model) {
  return {
    beatSpectrogram: async () => ({ frames: 1, mels: MELS, framesPerSecond: 50, values: new Float32Array(MELS) }),
    track: async () => model
  };
}

test('an agreeing model fixes the downbeat offset and raises confidence', async () => {
  const native = nativeResult({ bpm: 128 });
  const interval = 60 / 128;
  // The model heard the same beats, and puts bar one on native beat index 2.
  const model = {
    bpm: 128.2,
    beatConfidence: 0.95,
    beats: native.beats.slice(0, 60).map((time) => time - 10 + 0.004),
    downbeats: [2, 6, 10, 14, 18].map((index) => native.beats[index] - 10 + 0.004)
  };
  const merged = await refineBeatsWithModel(
    native,
    [{ samples: new Float32Array(10), sampleRate: 22050, offsetSeconds: 10 }],
    stubDeps(model)
  );
  assert.ok(merged);
  assert.equal(merged.beatConfidence, 0.95);
  assert.ok(Array.isArray(merged.downbeats));
  assert.ok(Math.abs(merged.downbeats[0] - native.beats[2]) < 1e-9);
  assert.ok(Math.abs(merged.downbeats[1] - native.beats[6]) < 1e-9);
});

test('a double-time model grid agrees after folding to the native metrical level', async () => {
  const native = nativeResult({ bpm: 85 });
  const nativeInterval = 60 / 85;
  const model = {
    bpm: 170,
    beatConfidence: 0.95,
    beats: Array.from(
      { length: 120 },
      (_, index) => native.beats[0] + index * (nativeInterval / 2)
    ),
    downbeats: []
  };
  const merged = await refineBeatsWithModel(
    native,
    [{ samples: new Float32Array(10), sampleRate: 22050, offsetSeconds: 0 }],
    stubDeps(model)
  );

  assert.ok(merged);
  assert.equal(merged.beatConfidence, 0.95);
  assert.ok(
    merged.beatModelAgreement < 0.01,
    `expected an aligned beat grid, got ${merged.beatModelAgreement}`
  );
});

test('a metrically ambiguous model reading is no opinion, not a demotion', async () => {
  // 3:2 against the native tempo: two defensible readings of one rhythm. This
  // exact case, read as disagreement, is what sank the aubio experiment.
  const native = nativeResult({ bpm: 128 });
  const model = {
    bpm: 192,
    beatConfidence: 0.9,
    beats: Array.from({ length: 60 }, (_, index) => index * (60 / 192)),
    downbeats: []
  };
  const merged = await refineBeatsWithModel(
    native,
    [{ samples: new Float32Array(10), sampleRate: 22050, offsetSeconds: 0 }],
    stubDeps(model)
  );
  assert.equal(merged, null);
});

test('octave-aligned tempo with misaligned beats loses beat-matching', async () => {
  const native = nativeResult({ bpm: 128 });
  const interval = 60 / 128;
  // Same tempo, but the model's beats sit on the offbeat: one tracker is
  // wrong and there is no way to know which.
  const model = {
    bpm: 128,
    beatConfidence: 0.95,
    beats: native.beats.slice(0, 60).map((time) => time - 10 + interval / 2),
    downbeats: []
  };
  const merged = await refineBeatsWithModel(
    native,
    [{ samples: new Float32Array(10), sampleRate: 22050, offsetSeconds: 10 }],
    stubDeps(model)
  );
  assert.ok(merged);
  assert.ok(merged.beatConfidence <= 0.45, `expected a demotion, got ${merged.beatConfidence}`);
  assert.equal(merged.downbeats, undefined);
});

test('a thin downbeat vote confirms beats without moving the bar line', async () => {
  const native = nativeResult({ bpm: 128 });
  const model = {
    bpm: 128,
    beatConfidence: 0.9,
    beats: native.beats.slice(0, 60).map((time) => time - 10),
    // Two bar lines are not enough evidence to re-phase every bar in the track.
    downbeats: [native.beats[2] - 10, native.beats[6] - 10]
  };
  const merged = await refineBeatsWithModel(
    native,
    [{ samples: new Float32Array(10), sampleRate: 22050, offsetSeconds: 10 }],
    stubDeps(model)
  );
  assert.ok(merged);
  assert.equal(merged.downbeats, undefined);
  assert.equal(merged.beatConfidence, 0.9);
});

test('trackBeats runs the whole chain against a stubbed session', async () => {
  // Logits with clean peaks every 25 frames (120 BPM at 50 fps), downbeats
  // every fourth beat. The stub stands in for ONNX Runtime so the test pins
  // the chunking, peak-picking, and snapping logic without the 23 MB model.
  const frames = 1488;
  const beatLogits = new Float32Array(frames).fill(-6);
  const downbeatLogits = new Float32Array(frames).fill(-6);
  for (let frame = 0; frame < frames; frame += 25) {
    beatLogits[frame] = 4;
    downbeatLogits[frame] = frame % 100 === 0 ? 3 : -6;
  }
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
          const count = feeds.input_spectrogram.dims[1];
          return {
            beat: { data: beatLogits.subarray(0, count) },
            downbeat: { data: downbeatLogits.subarray(0, count) }
          };
        }
      })
    }
  };
  const result = await trackBeats(
    { frames, mels: MELS, framesPerSecond: 50, values: flatSpectrogram(frames) },
    { modelPath: 'models/beat-this/beat_this_int8.onnx', load: async () => fakeRuntime }
  );
  assert.ok(result);
  assert.ok(Math.abs(result.bpm - 120) < 0.2, `expected 120, got ${result.bpm}`);
  assert.ok(result.beats.length >= 55);
  // Downbeats must be a strict subset of the beat grid after snapping.
  for (const downbeat of result.downbeats) {
    assert.ok(result.beats.includes(downbeat));
  }
});
