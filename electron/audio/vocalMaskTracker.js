// Vocal-presence masking with open-unmix's "vocals" target (Inria/SigSep,
// umxhq checkpoint).
//
// Why a model here at all: the existing mid-band duck (`mid_duck` in
// transition_render.cpp) kills a flat 200 Hz-4 kHz band by a flat amount that
// only follows the fade curve, not the music -- it ducks vocals whether or
// not there are any, and by the same amount for a whispered ad-lib as a
// full-throated hook. A real per-instant vocal-presence signal lets the
// renderer duck only when, and only as much as, the outgoing track is
// actually singing.
//
// open-unmix is the model that can ship: its umxhq weights are MIT (see
// models/vocal-separation/README.md for the paper trail), unlike Meta's
// htdemucs, whose weights are CC-BY-NC-4.0 -- the same non-commercial trap
// Essentia's models hit. Only the "vocals" target is used; Orchard needs
// vocal presence, not full 4-stem reconstruction, so drums/bass/other were
// never downloaded.
//
// Everything here is optional, matching beatThisTracker.js: no model file, no
// ONNX Runtime, an unreadable graph, a rate mismatch -- all resolve to null,
// and the caller keeps the flat mid_duck. A missing vocal mask makes the duck
// less precise; a throwing one would break a transition outright.

import path from 'node:path';
import { stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

// Matches native/analyzer/vocal_spectrogram.h and the fixed shape the model
// was exported at -- see scripts/convert-umx-vocals-to-onnx.py for why the
// export is fixed-frame rather than dynamic.
const BINS = 2049;
const FIXED_FRAMES = 960;

/** Default location of the model installed alongside the repository. */
export const DEFAULT_MODEL_PATH =
  path.join(here, '..', '..', 'models', 'vocal-separation', 'vocals_umxhq_int8.onnx');

// One session per process. Constructing it parses and optimizes the graph,
// which is too expensive to repeat per transition, and reuse across
// concurrent requests is safe.
let sessionPromise = null;

/** Discards the cached session. Exposed for tests and service shutdown. */
export function resetVocalMask() {
  sessionPromise = null;
}

async function session(modelPath, load) {
  if (!sessionPromise) {
    sessionPromise = (async () => {
      const info = await stat(modelPath).catch(() => null);
      if (!info?.isFile()) throw new Error(`Vocal separation model is not installed at ${modelPath}`);
      const runtime = await load();
      const InferenceSession = runtime?.InferenceSession || runtime?.default?.InferenceSession;
      const Tensor = runtime?.Tensor || runtime?.default?.Tensor;
      if (!InferenceSession || !Tensor) throw new Error('onnxruntime-node exports were unusable');
      const created = await InferenceSession.create(modelPath, {
        executionProviders: ['cpu'],
        graphOptimizationLevel: 'all',
        intraOpNumThreads: 2
      });
      return { created, Tensor };
    })().catch((error) => {
      sessionPromise = null;
      throw error;
    });
  }
  return sessionPromise;
}

/**
 * Zero-pads or truncates a [channels][bins][frames] spectrogram onto the
 * model's fixed frame budget, in place conceptually but returning a fresh
 * buffer since the input may be shorter or longer than the target.
 */
export function padToFixedFrames(values, channels, bins, frames, fixedFrames = FIXED_FRAMES) {
  const padded = new Float32Array(channels * bins * fixedFrames);
  const copyFrames = Math.min(frames, fixedFrames);
  for (let channel = 0; channel < channels; channel += 1) {
    for (let bin = 0; bin < bins; bin += 1) {
      const sourceBase = (channel * bins + bin) * frames;
      const targetBase = (channel * bins + bin) * fixedFrames;
      for (let frame = 0; frame < copyFrames; frame += 1) {
        padded[targetBase + frame] = values[sourceBase + frame];
      }
    }
  }
  return padded;
}

/**
 * Reduces the model's per-bin mask into a compact time curve over a single
 * frequency band, by averaging `mask = target / (mix + eps)` across the
 * band's bins at each frame, then across channels.
 *
 * Band-averaging is deliberate, not a simplification of convenience: the
 * renderer applies the duck as a gain on a fixed EQ band (mirroring
 * `mid_duck`'s own bandpass), so a curve at that same band's granularity is
 * exactly what it can act on -- shipping a full per-bin mask across IPC and
 * into native code would be work with no consumer for the extra resolution.
 *
 * `strideFrames` is the frame stride the arrays are actually stored at (the
 * model's fixed input width after padding), which is not the same as
 * `usableFrames` (how many of those frames hold real audio rather than
 * padded silence) whenever the input was shorter than the model's budget --
 * conflating the two would either read past real data with the wrong stride
 * or fold the meaningless mask of padded silence into the curve.
 */
export function reduceMaskToBandCurve(mix, target, {
  channels,
  bins,
  strideFrames,
  usableFrames,
  sampleRate,
  fftSize,
  lowHz,
  highHz
}) {
  const lowBin = Math.max(0, Math.floor((lowHz * fftSize) / sampleRate));
  const highBin = Math.min(bins - 1, Math.ceil((highHz * fftSize) / sampleRate));
  if (highBin <= lowBin || usableFrames <= 0) return [];

  const curve = new Array(usableFrames).fill(0);
  for (let frame = 0; frame < usableFrames; frame += 1) {
    let sum = 0;
    let count = 0;
    for (let channel = 0; channel < channels; channel += 1) {
      for (let bin = lowBin; bin <= highBin; bin += 1) {
        const index = (channel * bins + bin) * strideFrames + frame;
        const mixValue = mix[index];
        if (!(mixValue > 1e-6)) continue;
        const ratio = target[index] / mixValue;
        sum += Math.max(0, Math.min(1, ratio));
        count += 1;
      }
    }
    curve[frame] = count > 0 ? sum / count : 0;
  }
  return curve;
}

/**
 * Runs the vocal-separation model over one spectrogram and returns a
 * band-averaged vocal-presence curve, one value per STFT frame in [0, 1].
 *
 * @param {{values: Float32Array, frames: number, channels: number, bins: number,
 *   framesPerSecond: number}} spectrogram
 * @returns {Promise<null|{curve: number[], framesPerSecond: number}>} null
 *   whenever the model cannot be used, which is a routing decision (fall back
 *   to the flat mid_duck) rather than an error.
 */
export async function trackVocalMask(spectrogram, {
  modelPath = DEFAULT_MODEL_PATH,
  load = () => import('onnxruntime-node'),
  sampleRate = 44100,
  fftSize = 4096,
  lowHz = 200,
  highHz = 4000
} = {}) {
  const frames = Number(spectrogram?.frames) || 0;
  const channels = Number(spectrogram?.channels) || 0;
  const bins = Number(spectrogram?.bins) || 0;
  const fps = Number(spectrogram?.framesPerSecond) || 0;
  if (!frames || channels !== 2 || bins !== BINS || !fps || !(spectrogram?.values?.length >= channels * bins * frames)) {
    return null;
  }
  if (frames > FIXED_FRAMES) return null;

  let engine;
  try {
    engine = await session(modelPath, load);
  } catch {
    return null;
  }

  try {
    const padded = padToFixedFrames(spectrogram.values, channels, bins, frames, FIXED_FRAMES);
    const tensor = new engine.Tensor('float32', padded, [1, channels, bins, FIXED_FRAMES]);
    const output = await engine.created.run({ mix_magnitude: tensor });
    const target = output.target_magnitude?.data;
    if (!target) return null;

    // Only the frames that carry real audio matter; the padded tail's mask is
    // meaningless (computed from silence) and must not leak into the curve.
    const curve = reduceMaskToBandCurve(padded, target, {
      channels, bins, strideFrames: FIXED_FRAMES, usableFrames: frames,
      sampleRate, fftSize, lowHz, highHz
    });
    return { curve, framesPerSecond: fps };
  } catch {
    return null;
  }
}
