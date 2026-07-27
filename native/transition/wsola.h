// Waveform-Similarity Overlap-Add time scaling for offline transition
// rendering. `channels` is planar (non-interleaved) Float32 PCM in [-1, 1],
// every channel the same length, matching the layout AudioBuffer exposes.
//
// The similarity search runs once on a mono downmix and the resulting input
// offsets are applied to every channel, so a stereo image survives the stretch
// intact; searching per channel would decorrelate them and collapse the image.
//
// Calls borrow the input only until they return and produce results that own
// all storage. Stretching is reentrant because every mutable value is
// call-local. It allocates and performs O(n * search_radius) work, so it
// belongs on a worker thread and must never run in a real-time audio callback.

#pragma once

#include <cstddef>
#include <vector>

namespace orchard {

struct WsolaConfig {
  // Output length divided by input length. Values above 1 stretch the signal
  // (playing it slower); values below 1 compress it. To retune a track of
  // tempo `source_bpm` onto a grid of `target_bpm`, use source_bpm/target_bpm.
  double ratio = 1.0;
  // Ratio to begin at before gliding to `ratio`, letting a track slide onto a
  // new tempo the way a DJ rides a pitch fader instead of stepping onto it.
  // Zero disables the glide and holds `ratio` throughout.
  double start_ratio = 0;
  // Fraction of the input over which `start_ratio` reaches `ratio`. Ignored
  // when there is no glide; clamped to (0, 1].
  double glide = 0;
  // Analysis/synthesis window in samples. Zero derives ~46 ms from the sample
  // rate, which keeps bass periods intact on four-to-the-floor material.
  int frame_size = 0;
  // Half-width of the similarity search in samples. Zero derives a quarter of
  // the frame, enough to slide over one period of a low bass note.
  int search_radius = 0;
};

// The largest deviation from unity this implementation is willing to render.
// WSOLA stays transparent on music to roughly +/-8%; past that it audibly
// stutters on sustained tones, so callers should reject the pairing instead.
inline constexpr double kMaxTransparentRatioDeviation = 0.08;

/**
 * Time-scales planar audio by `config.ratio` while preserving pitch.
 *
 * Returns an empty vector when the input is empty, ragged, or the resolved
 * configuration cannot produce a frame; the caller is expected to treat that
 * as "do not attempt this transition" rather than as a fatal error. A ratio of
 * exactly 1 still round-trips through the overlap-add path so that callers get
 * identical framing behaviour regardless of tempo.
 */
std::vector<std::vector<float>> WsolaStretch(
  const std::vector<std::vector<float>>& channels,
  double sample_rate,
  const WsolaConfig& config
);

}  // namespace orchard
