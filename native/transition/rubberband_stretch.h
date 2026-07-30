// Time-scales planar audio with the vendored Rubber Band Library (R3/"Finer"
// engine, offline mode), replacing the hand-rolled WSOLA implementation this
// file used to be.
//
// Why the replacement: WSOLA's textbook transparency figure is +/-8%, but
// that is measured on isolated instruments. On the dense full mixes a DJ
// transition actually stretches -- two kick drums, stacked vocals -- the
// hand-rolled version doubled transients and warbled sustained vocals well
// before that. Rubber Band's R3 engine is specifically tuned for exactly this
// material ("complex mixes, vocals and other sounds that have soft onsets...
// and music with substantial bass content", per its own documentation).
//
// `channels` is planar (non-interleaved) Float32 PCM in [-1, 1], every
// channel the same length, matching the layout AudioBuffer exposes and what
// the old WSOLA function expected -- this is a drop-in replacement at the
// call site in transition_render.cpp.
//
// Calls borrow the input only until they return and produce results that own
// all storage. The work is O(n) and allocates, so it belongs on a worker
// thread and must never run in a real-time audio callback -- same
// requirement as before, and Rubber Band's offline mode is not real-time
// safe by design anyway (it buffers the whole input across two passes).

#pragma once

#include <cstddef>
#include <vector>

namespace orchard {

// The largest deviation from unity this implementation is willing to render.
//
// Carried over unchanged from the WSOLA implementation rather than
// re-measured and loosened: R3 is a better engine on the same material, but
// "better" was established by ear on real transitions, and this number
// should only move on the same basis, not by assumption that a better engine
// automatically earns a wider window.
inline constexpr double kMaxTransparentRatioDeviation = 0.04;

/**
 * Time-scales `channels` by `ratio` (output length / input length) while
 * preserving pitch. A ratio above 1 slows the audio down; below 1 speeds it
 * up -- same convention as the WSOLA function this replaces, so a caller
 * computing `outgoing_bpm / incoming_bpm` needs no change.
 *
 * Returns an empty vector when the input is empty, ragged, or the ratio is
 * not a finite positive number; the caller is expected to treat that as "do
 * not attempt this transition" rather than as a fatal error.
 */
std::vector<std::vector<float>> RubberBandTimeStretch(
  const std::vector<std::vector<float>>& channels,
  double sample_rate,
  double ratio
);

}  // namespace orchard
