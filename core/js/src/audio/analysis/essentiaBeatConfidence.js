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

// Refines the native analyzer's beat confidence using Essentia's
// RhythmExtractor2013 in multifeature mode.
//
// The native analyzer's own confidence blends how strongly its autocorrelation
// peaked, how strongly the chosen phase scored, and how far the winner sat from
// the runner-up. Those all measure how cleanly the method fit its own model,
// which is not the same as being right: measured against four real tracks it
// returned 0.34, 0.67, 0.46 and 0.50 on tempi that were correct to within 0.3%,
// so three of the four fell under the beat-matching threshold and lost a
// transition they should have had.
//
// Essentia's confidence is calibrated against an accuracy measure instead of
// against its own fit, and its documented bands are what MapConfidence below is
// anchored to. Its *tempo* is not used: on the one file with known ground truth
// (a Spotify AutoMix capture measured at 126.05 BPM) the native analyzer read
// 126.00 and Essentia read 123.81, so the native estimate stays authoritative.
//
// Cost is why this is scoped rather than universal. Essentia's rhythm extractor
// alone runs about 3.6x the whole native analysis (1224 ms against 339 ms for a
// 139 s track at 11,025 Hz). That is trivial for the one or two tracks around a
// transition and ruinous for Best Mix, which analyses up to fifty.

// Documented interpretation of RhythmExtractor2013's multifeature confidence,
// which is bounded to [0, 5.32]:
//
//   [0, 1)     very low -- the signal defeats the candidate beat trackers
//   [1, 1.5]   low
//   (1.5, 3.5] good, around 80% accuracy in the AMLt measure
//   (3.5, ...] excellent
//
// Orchard's policy tiers read a [0, 1] confidence and treat 0.2 as the floor
// for DJ-assisted transitions and 0.55 as the floor for beat-matching. Anchoring
// those thresholds to these bands is the point of the exercise: "good enough to
// beat-match" becomes Essentia's ~80%-accuracy band rather than a number tuned
// by hand.
const CONFIDENCE_BANDS = [
  { essentia: 0, orchard: 0.05 },
  { essentia: 1.0, orchard: 0.2 },
  { essentia: 1.5, orchard: 0.4 },
  { essentia: 3.5, orchard: 0.8 },
  { essentia: 5.32, orchard: 1.0 }
];

export const ESSENTIA_CONFIDENCE_MAX = 5.32;

// Below the "low" band Essentia documents its output as meaning the input is
// hard *for its candidate beat trackers* -- a statement about the trackers, not
// a verdict on the track. Letting that override a healthy native confidence
// would repeat the mistake that sank the aubio experiment, where a tracker's
// own uncertainty was charged against an estimate that turned out correct. So
// a very-low reading is treated as "no opinion" and the native value stands.
export const MIN_USABLE_ESSENTIA_CONFIDENCE = 1.0;

/** Piecewise-linear map from Essentia's [0, 5.32] onto Orchard's [0, 1]. */
export function mapConfidence(value) {
  const confidence = Number(value);
  if (!Number.isFinite(confidence) || confidence <= 0) return CONFIDENCE_BANDS[0].orchard;
  for (let index = 1; index < CONFIDENCE_BANDS.length; index += 1) {
    const high = CONFIDENCE_BANDS[index];
    if (confidence > high.essentia && index < CONFIDENCE_BANDS.length - 1) continue;
    const low = CONFIDENCE_BANDS[index - 1];
    const span = high.essentia - low.essentia;
    const position = span > 0 ? Math.min(1, (confidence - low.essentia) / span) : 1;
    return Math.max(0, Math.min(1, low.orchard + position * (high.orchard - low.orchard)));
  }
  return 1;
}

// The WASM module and its heap are expensive to construct and safe to reuse, so
// one instance is kept for the process. Loading is lazy because a session that
// never plays a smart transition should never pay for it.
let instancePromise = null;

async function essentia(load) {
  if (!instancePromise) {
    instancePromise = (async () => {
      const pkg = await load();
      const Essentia = pkg.Essentia || pkg.default?.Essentia;
      const wasm = pkg.EssentiaWASM || pkg.default?.EssentiaWASM;
      if (typeof Essentia !== 'function' || !wasm) throw new Error('essentia.js exports were unusable');
      return new Essentia(wasm);
    })().catch((error) => {
      // A failed load must not poison every later call; the next attempt retries.
      instancePromise = null;
      throw error;
    });
  }
  return instancePromise;
}

/** Discards the cached instance. Exposed for tests and service shutdown. */
export function resetEssentia() {
  instancePromise = null;
}

/**
 * Returns `{ beatConfidence, essentiaConfidence, essentiaBpm }` for mono PCM, or
 * null when Essentia is unavailable, too slow, or returns nothing usable. A null
 * return is a routing decision, not an error: the caller keeps the native
 * confidence rather than failing the analysis.
 *
 * `timeoutMs` bounds the wait, not the work — the WASM call is synchronous and
 * cannot be interrupted once entered, so this guards the *scheduling* of a call
 * whose input is larger than expected rather than aborting one in flight.
 */
export async function refineBeatConfidence(samples, sampleRate, {
  timeoutMs = 10_000,
  load = () => import('essentia.js'),
  now = () => Date.now()
} = {}) {
  if (!(samples?.length > 0) || !Number.isFinite(sampleRate) || sampleRate < 1000) return null;
  const startedAt = now();
  let engine;
  try {
    engine = await essentia(load);
  } catch {
    return null;
  }
  // Loading the module may itself have consumed the budget.
  if (now() - startedAt >= timeoutMs) return null;

  let vector = null;
  try {
    vector = engine.arrayToVector(samples);
    const result = engine.RhythmExtractor2013(vector, 208, 'multifeature', 40);
    const essentiaConfidence = Number(result?.confidence);
    if (!Number.isFinite(essentiaConfidence)) return null;
    if (essentiaConfidence < MIN_USABLE_ESSENTIA_CONFIDENCE) return null;
    return {
      beatConfidence: mapConfidence(essentiaConfidence),
      essentiaConfidence,
      essentiaBpm: Number(result?.bpm) || 0,
      elapsedMs: now() - startedAt
    };
  } catch {
    return null;
  } finally {
    // The vector is WASM heap memory and is not garbage collected with the JS
    // wrapper; leaking one per analysed track would grow the heap unboundedly.
    try {
      vector?.delete();
    } catch {
      // Already released or the module is gone; nothing further to do.
    }
  }
}
