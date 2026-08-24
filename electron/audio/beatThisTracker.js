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

// Beat and downbeat tracking with the Beat This! model (CPJKU, ISMIR 2024).
//
// Why a model at all, when the native analyzer already finds a tempo: tempo and
// *meter* are different problems. Orchard's autocorrelation reads tempo well --
// measured against a Spotify AutoMix capture of known 126.05 BPM it returned
// 126.00 -- but nothing in an autocorrelation says which of four beats is beat
// one. The analyzer's fallback answer is bass-band onset strength, which finds
// the kick and is right on ordinary backbeat material and a coin flip on
// four-on-the-floor, where every beat has the same kick. A mix that enters on
// beat three of the bar sounds wrong even when every beat lines up, so that
// coin flip is worth a model.
//
// Beat This! is the one that can actually be shipped: both its code and its
// trained weights are MIT. Essentia's rhythm extractor has no downbeat tracker
// at all, and Essentia's pretrained models are CC BY-NC-SA, which an
// AGPL application distributed to users cannot use.
//
// Everything here is optional. No model file, no ONNX Runtime, an unreadable
// graph, a rate mismatch -- all resolve to null, and the caller keeps the
// native grid. A missing beat tracker degrades transitions; a throwing one
// would break analysis outright.

import path from 'node:path';
import { stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { loadOnnxModel, loadOnnxRuntime, onnxExecutionProviders } from './onnxRuntime.js';

const here = path.dirname(fileURLToPath(import.meta.url));

// The window the model was trained on, and the margin discarded from each
// chunk's edges where it has no context. Both come from the upstream inference
// code; changing them changes the predictions.
const CHUNK_FRAMES = 1500;
const BORDER_FRAMES = 6;

// Peak picking, also from upstream: a frame is a beat when it is the maximum of
// a seven-frame window and its logit is positive.
const PEAK_WINDOW = 7;

// Plausible musical tempo, used only to reject a grid the model clearly did not
// find -- a handful of scattered peaks across a track would otherwise produce a
// nonsense BPM that the transition planner would take at face value.
const MIN_BPM = 40;
const MAX_BPM = 220;

/**
 * The committed model: the published fp32 weights dynamically quantized to
 * int8 (models/beat-this/README.md records the derivation). Measured against
 * the fp32 original on the synthetic harness the predictions are identical to
 * within peak-picking noise, at half the inference time and 23 MB instead
 * of 83.
 */
export const DEFAULT_MODEL_PATH =
  path.join(here, '..', '..', 'models', 'beat-this', 'beat_this_int8.onnx');

/**
 * Window length that costs exactly one model inference. The model reads
 * 1500-frame chunks with a 6-frame border at 50 fps; a window longer than
 * (1500 - 12) frames splits into two chunks that mostly overlap -- upstream's
 * "avoid short end" rule pulls the second chunk back over the first -- so a
 * 60 s window pays three inferences for 60 s of audio where two 29.76 s
 * windows pay two.
 */
export const BEAT_WINDOW_SECONDS = (CHUNK_FRAMES - 2 * BORDER_FRAMES) / 50;

// One session per process. Constructing it parses and optimizes an 83 MB graph,
// which is far too expensive to repeat per track, and it is safe to reuse
// across concurrent runs.
let sessionPromise = null;

/** Discards the cached session. Exposed for tests and service shutdown. */
export function resetBeatThis() {
  sessionPromise = null;
}

async function session(modelPath, load) {
  if (!sessionPromise) {
    sessionPromise = (async () => {
      const info = await stat(modelPath).catch(() => null);
      if (!info?.isFile()) throw new Error(`Beat This model is not installed at ${modelPath}`);
      const runtime = await load();
      const model = await loadOnnxModel(modelPath);
      const InferenceSession = runtime?.InferenceSession || runtime?.default?.InferenceSession;
      const Tensor = runtime?.Tensor || runtime?.default?.Tensor;
      if (!InferenceSession || !Tensor) throw new Error('ONNX Runtime exports were unusable');
      const created = await InferenceSession.create(model, {
        executionProviders: onnxExecutionProviders(),
        graphOptimizationLevel: 'all',
        // Analysis runs while audio is playing. Leaving this at the default
        // takes every core and has been the cause of glitching in other
        // Electron apps that run inference on the main process.
        intraOpNumThreads: 2
      });
      return { created, Tensor };
    })().catch((error) => {
      // A failed load must not poison every later call; the next attempt retries.
      sessionPromise = null;
      throw error;
    });
  }
  return sessionPromise;
}

/**
 * Splits a spectrogram into the overlapping windows the model expects.
 * Mirrors `split_piece` in the upstream inference code, including its
 * "avoid short end" adjustment, which pulls the final window back so the last
 * chunk is full length rather than a stub the model has no context for.
 *
 * @returns {Array<{start: number, frames: number, values: Float32Array}>}
 */
export function splitSpectrogram(values, frames, mels) {
  const step = CHUNK_FRAMES - 2 * BORDER_FRAMES;
  const starts = [];
  for (let start = -BORDER_FRAMES; start < frames - BORDER_FRAMES; start += step) {
    starts.push(start);
  }
  if (!starts.length) starts.push(-BORDER_FRAMES);
  if (frames > step) starts[starts.length - 1] = frames - (CHUNK_FRAMES - BORDER_FRAMES);
  // Upstream's "avoid short end" rule re-anchors the final chunk so it is full
  // length, but when the audio only *barely* overruns a chunk it degenerates:
  // a 1489-frame window (what a 29.76 s window's spectrogram actually is)
  // spends a second full inference to predict one extra frame. A tail chunk
  // has to earn its inference; the frames it would have covered are left
  // unpredicted, which peak picking treats as silence at the window's edge.
  while (starts.length > 1 && starts[starts.length - 1] - starts[starts.length - 2] < 2 * BORDER_FRAMES) {
    starts.pop();
  }

  return starts.map((start) => {
    const from = Math.max(0, start);
    const to = Math.min(start + CHUNK_FRAMES, frames);
    const leftPad = Math.max(0, -start);
    const rightPad = Math.max(0, Math.min(BORDER_FRAMES, start + CHUNK_FRAMES - frames));
    const length = Math.max(0, to - from) + leftPad + rightPad;
    const chunk = new Float32Array(length * mels);
    if (to > from) {
      chunk.set(values.subarray(from * mels, to * mels), leftPad * mels);
    }
    return { start, frames: length, values: chunk };
  });
}

/**
 * Stitches per-chunk logits back onto the whole-track timeline, discarding each
 * chunk's border where the model was predicting without context. Chunks are
 * applied last-to-first so the earliest prediction for any frame wins, matching
 * upstream's "keep_first" overlap mode.
 */
export function aggregateLogits(chunks, frames) {
  const beat = new Float32Array(frames).fill(-1000);
  const downbeat = new Float32Array(frames).fill(-1000);
  for (let index = chunks.length - 1; index >= 0; index -= 1) {
    const { start, beatLogits, downbeatLogits } = chunks[index];
    const length = beatLogits.length;
    // A chunk shorter than its own borders has no interior to contribute; take
    // all of it rather than nothing.
    const from = length < 2 * BORDER_FRAMES ? 0 : BORDER_FRAMES;
    const to = length < 2 * BORDER_FRAMES ? length : length - BORDER_FRAMES;
    for (let offset = from; offset < to; offset += 1) {
      const target = start + offset;
      if (target < 0 || target >= frames) continue;
      beat[target] = beatLogits[offset];
      downbeat[target] = downbeatLogits[offset];
    }
  }
  return { beat, downbeat };
}

/**
 * Frame indices that are local maxima of `logits` over PEAK_WINDOW and positive,
 * with runs of adjacent peaks collapsed to their mean, then refined to
 * sub-frame resolution.
 *
 * The refinement is Orchard's addition, not upstream's. The model's frame rate
 * is 50 Hz, so an integer peak quantizes every beat to 20 ms -- fine for
 * displaying a grid, and about a fifth of the flam threshold budget for
 * beat-matching two tracks against each other. Fitting a parabola through the
 * peak and its neighbours recovers where the maximum actually sits.
 */
export function pickPeaks(logits) {
  const half = Math.floor(PEAK_WINDOW / 2);
  const peaks = [];
  for (let index = 0; index < logits.length; index += 1) {
    if (!(logits[index] > 0)) continue;
    let isMaximum = true;
    for (let offset = -half; offset <= half; offset += 1) {
      const neighbour = index + offset;
      if (neighbour < 0 || neighbour >= logits.length) continue;
      if (logits[neighbour] > logits[index]) {
        isMaximum = false;
        break;
      }
    }
    if (isMaximum) peaks.push(index);
  }

  // Collapse adjacent frames that tied for the maximum onto their mean.
  const deduped = [];
  for (let index = 0; index < peaks.length; index += 1) {
    let mean = peaks[index];
    let count = 1;
    while (index + 1 < peaks.length && peaks[index + 1] - mean <= 1) {
      index += 1;
      count += 1;
      mean += (peaks[index] - mean) / count;
    }
    deduped.push(Math.round(mean));
  }

  return deduped.map((frame) => {
    if (frame <= 0 || frame + 1 >= logits.length) return frame;
    const left = logits[frame - 1];
    const centre = logits[frame];
    const right = logits[frame + 1];
    const denominator = left - 2 * centre + right;
    if (!(Math.abs(denominator) > 1e-9)) return frame;
    const shift = (0.5 * (left - right)) / denominator;
    return frame + Math.max(-0.5, Math.min(0.5, shift));
  });
}

function median(values) {
  if (!values.length) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.floor(sorted.length / 2)];
}

/**
 * Tempo from a tracked grid: the median spacing of beats that are actually
 * consecutive. Outliers are dropped first, because a grid with a missed beat
 * has a double-length gap in it, and averaging that in reports a tempo the
 * track never played.
 */
export function tempoFromBeats(beats) {
  if (beats.length < 8) return 0;
  const gaps = [];
  for (let index = 1; index < beats.length; index += 1) gaps.push(beats[index] - beats[index - 1]);
  const rough = median(gaps);
  if (!(rough > 0)) return 0;
  const kept = gaps.filter((gap) => Math.abs(gap - rough) <= rough * 0.2);
  const interval = median(kept.length >= 4 ? kept : gaps);
  if (!(interval > 0)) return 0;
  const bpm = 60 / interval;
  return bpm >= MIN_BPM && bpm <= MAX_BPM ? bpm : 0;
}

/**
 * How much to trust a model-supplied grid, on Orchard's [0, 1] scale.
 *
 * The model exposes no confidence of its own, so this is a proxy and is
 * documented as one: it reads how decisively the beat logits peaked and how
 * regular the resulting grid is. The floor matters more than the exact curve --
 * a grid this tracker produced at all is better evidence than the
 * autocorrelation's self-fit score, so a clean one clears the 0.55
 * beat-matching threshold, and only a visibly irregular or weakly-peaked grid
 * falls back under it.
 */
export function gridConfidence(beats, peakLogits) {
  if (beats.length < 8) return 0;
  const gaps = [];
  for (let index = 1; index < beats.length; index += 1) gaps.push(beats[index] - beats[index - 1]);
  const interval = median(gaps);
  if (!(interval > 0)) return 0;

  // Fraction of gaps that are one beat rather than a hole in the grid.
  const regular = gaps.filter((gap) => Math.abs(gap - interval) <= interval * 0.1).length / gaps.length;
  // Logits are unbounded; a median around 2 is a decisive peak, around 0 is not.
  const strength = 1 / (1 + Math.exp(-(median(peakLogits) - 0.5)));
  return Math.max(0, Math.min(0.95, 0.35 + 0.4 * regular + 0.25 * strength));
}

/**
 * Runs the model over a whole-track log-mel spectrogram.
 *
 * @param {{values: Float32Array, frames: number, mels: number, framesPerSecond: number}} spectrogram
 * @returns {Promise<null|{beats: number[], downbeats: number[], bpm: number,
 *   beatInterval: number, firstBeat: number, beatConfidence: number, elapsedMs: number}>}
 *   null whenever the model cannot be used or did not find a usable grid, which
 *   is a routing decision rather than an error.
 */
/**
 * Folds the model's opinion into a native analysis, using windows of PCM
 * around the head and tail of the track -- the regions where a transition's
 * mix-in and mix-out anchors live.
 *
 * Division of labour, deliberately narrow: the native phase-locked grid keeps
 * the beats (measured at ~4 ms against known phase, and it covers the whole
 * track where the model only saw two windows). The model decides which beat is
 * beat one, because bar phase is global -- beat index mod 4 propagates down
 * the entire grid -- so a windowed vote fixes every downbeat in the track.
 *
 * The rules for disagreement follow what sank the aubio experiment:
 * - Tempi are compared after octave alignment, and a 3:2 or 4:3 relation is
 *   treated as "no opinion" (returns null), never as evidence against the
 *   native estimate -- those are two defensible readings of one rhythm.
 * - Only an octave-compatible tempo with *misaligned beats* demotes: that
 *   means at least one tracker is on the offbeat, and beat-matching on a grid
 *   two trackers place differently is how a mix ends up half a beat out.
 *
 * @returns null when the model has no usable opinion (caller falls back to the
 *   Essentia confidence pass); otherwise an object of fields to merge over the
 *   native result.
 */
export async function refineBeatsWithModel(rawResult, windows, {
  beatSpectrogram,
  track = trackBeats,
  modelPath = DEFAULT_MODEL_PATH,
  log = () => {}
} = {}) {
  const nativeBeats = Array.isArray(rawResult?.beats) ? rawResult.beats.map(Number) : [];
  const nativeBpm = Number(rawResult?.bpm) || 0;
  if (nativeBeats.length < 8 || !(nativeBpm > 0) || typeof beatSpectrogram !== 'function') {
    return null;
  }
  const interval = 60 / nativeBpm;

  const nearestIndex = (time) => {
    // The beats are sorted; binary search keeps the vote O(n log n) even on
    // ten-minute grids.
    let low = 0;
    let high = nativeBeats.length - 1;
    while (high - low > 1) {
      const mid = (low + high) >> 1;
      if (nativeBeats[mid] < time) low = mid;
      else high = mid;
    }
    return Math.abs(nativeBeats[low] - time) <= Math.abs(nativeBeats[high] - time) ? low : high;
  };

  const nearestDistance = (grid, time) => {
    if (!grid.length) return Infinity;
    if (grid.length === 1) return Math.abs(grid[0] - time);
    let low = 0;
    let high = grid.length - 1;
    while (high - low > 1) {
      const mid = (low + high) >> 1;
      if (grid[mid] < time) low = mid;
      else high = mid;
    }
    return Math.min(Math.abs(grid[low] - time), Math.abs(grid[high] - time));
  };

  const offsetVotes = [0, 0, 0, 0];
  const agreements = [];
  let modelConfidence = 0;
  let modelBpm = 0;

  for (const window of Array.isArray(windows) ? windows : []) {
    const samples = window?.samples;
    const sampleRate = Number(window?.sampleRate) || 0;
    const offsetSeconds = Number(window?.offsetSeconds) || 0;
    if (!(samples?.length > 0) || !(sampleRate > 0)) continue;

    const spectrogram = await beatSpectrogram(samples, sampleRate);
    const found = await track(spectrogram, { modelPath });
    if (!found) continue;

    // Octave alignment first. The model reporting 174 where the native grid
    // says 87 is one rhythm read at two levels, not a contradiction.
    const ratio = found.bpm / nativeBpm;
    const octaves = Math.round(Math.log2(ratio));
    const aligned = ratio / 2 ** octaves;
    if (Math.abs(aligned - 1) > 0.06) {
      // Half-way readings (3:2, 4:3) are exactly the case the aubio experiment
      // mislabelled as disagreement. No opinion, not a demotion.
      const metrical = [1.5, 2 / 3, 4 / 3, 0.75].some(
        (candidate) => Math.abs(ratio / candidate - 1) < 0.06
      );
      log('beat-model-window-tempo-mismatch', {
        modelBpm: found.bpm,
        nativeBpm,
        metrical
      });
      continue;
    }

    // Compare phase at the same metrical level. Tempo octave alignment alone
    // is insufficient: when the model reports 170 against an 85 BPM native
    // grid, every other model beat is intentionally a native offbeat. Fold the
    // faster grid and choose its best phase before deciding the trackers
    // disagree. A genuine same-level offbeat disagreement remains untouched.
    const shiftedModelBeats = found.beats.map((time) => time + offsetSeconds);
    const fold = 2 ** Math.abs(octaves);
    let phaseDistances;
    if (octaves > 0 && fold > 1) {
      phaseDistances = Array.from({ length: fold }, (_, phase) => shiftedModelBeats
        .filter((_, index) => index % fold === phase)
        .map((time) => Math.abs(nativeBeats[nearestIndex(time)] - time)));
    } else if (octaves < 0 && fold > 1) {
      phaseDistances = Array.from({ length: fold }, (_, phase) => {
        const foldedNativeBeats = nativeBeats.filter((_, index) => index % fold === phase);
        return shiftedModelBeats.map((time) => nearestDistance(foldedNativeBeats, time));
      });
    } else {
      phaseDistances = [shiftedModelBeats.map(
        (time) => Math.abs(nativeBeats[nearestIndex(time)] - time)
      )];
    }
    const distances = phaseDistances.reduce((best, candidate) => (
      !best || median(candidate) < median(best) ? candidate : best
    ), null) || [];
    const agreement = distances.length ? median(distances) / interval : 1;
    agreements.push(agreement);
    modelConfidence = Math.max(modelConfidence, Number(found.beatConfidence) || 0);
    modelBpm = modelBpm || found.bpm;
    if (agreement > 0.3) continue;

    for (const downbeat of found.downbeats) {
      offsetVotes[nearestIndex(downbeat + offsetSeconds) % 4] += 1;
    }
  }

  if (!agreements.length) {
    // Either the model found nothing or every window was metrically ambiguous.
    return null;
  }

  const bestAgreement = Math.min(...agreements);
  if (bestAgreement > 0.3) {
    // Tempo matches but the beats do not: one of the two grids is on the
    // offbeat, and there is no way to know which from here. Beat-matching on
    // that grid risks a mix that is audibly half a beat out for its entire
    // length, so it loses its authorization.
    log('beat-model-phase-disagreement', { agreement: bestAgreement });
    return {
      beatConfidence: Math.min(Number(rawResult.beatConfidence) || 0, 0.45),
      nativeBeatConfidence: Number(rawResult.beatConfidence) || 0,
      beatModelChecked: true,
      beatModelAgreement: bestAgreement,
      beatModelBpm: modelBpm
    };
  }

  const totalVotes = offsetVotes.reduce((sum, count) => sum + count, 0);
  const winner = offsetVotes.indexOf(Math.max(...offsetVotes));
  const merged = {
    nativeBeatConfidence: Number(rawResult.beatConfidence) || 0,
    beatConfidence: Math.max(Number(rawResult.beatConfidence) || 0, modelConfidence),
    beatModelChecked: true,
    beatModelAgreement: bestAgreement,
    beatModelBpm: modelBpm
  };
  // A vote this thin (a window with two or three bar lines) is not enough to
  // overturn the native offset; confidence still improves because the beats
  // themselves were confirmed.
  if (totalVotes >= 4 && offsetVotes[winner] > totalVotes / 2) {
    merged.downbeats = nativeBeats.filter((_, index) => index % 4 === winner % 4);
  }
  return merged;
}

export async function trackBeats(spectrogram, {
  modelPath = DEFAULT_MODEL_PATH,
  load = loadOnnxRuntime,
  now = () => Date.now()
} = {}) {
  const frames = Number(spectrogram?.frames) || 0;
  const mels = Number(spectrogram?.mels) || 0;
  const fps = Number(spectrogram?.framesPerSecond) || 0;
  if (!frames || !mels || !fps || !(spectrogram?.values?.length >= frames * mels)) return null;

  const startedAt = now();
  let engine;
  try {
    engine = await session(modelPath, load);
  } catch {
    return null;
  }

  try {
    const chunks = splitSpectrogram(spectrogram.values, frames, mels);
    const predicted = [];
    for (const chunk of chunks) {
      if (!chunk.frames) continue;
      const tensor = new engine.Tensor('float32', chunk.values, [1, chunk.frames, mels]);
      const output = await engine.created.run({ input_spectrogram: tensor });
      const beatLogits = output.beat?.data;
      const downbeatLogits = output.downbeat?.data;
      if (!beatLogits || !downbeatLogits) return null;
      predicted.push({ start: chunk.start, beatLogits, downbeatLogits });
    }
    if (!predicted.length) return null;

    const { beat, downbeat } = aggregateLogits(predicted, frames);
    const beatFrames = pickPeaks(beat);
    const downbeatFrames = pickPeaks(downbeat);
    const beats = beatFrames.map((frame) => frame / fps);
    if (beats.length < 8) return null;

    // Every downbeat is a beat. The two heads are predicted independently, so
    // their peaks can land a frame apart; snapping the downbeat onto the beat
    // it belongs to keeps the bar grid a strict subset of the beat grid, which
    // is what the planner assumes when it snaps a transition to a downbeat.
    const downbeats = [...new Set(
      downbeatFrames
        .map((frame) => frame / fps)
        .map((time) => beats.reduce(
          (best, value) => (Math.abs(value - time) < Math.abs(best - time) ? value : best),
          beats[0]
        ))
    )].sort((left, right) => left - right);

    const bpm = tempoFromBeats(beats);
    if (!bpm) return null;

    return {
      beats,
      downbeats,
      bpm,
      beatInterval: 60 / bpm,
      firstBeat: beats[0],
      beatConfidence: gridConfidence(beats, beatFrames.map((frame) => beat[Math.round(frame)] ?? 0)),
      elapsedMs: now() - startedAt
    };
  } catch {
    return null;
  }
}
