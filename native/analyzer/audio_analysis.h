// Offline whole-track analysis shared by the Node binding and the DSP stages.
// `samples` is contiguous, non-interleaved mono Float32 PCM. Callers normally
// supply finite normalized amplitudes in [-1, 1], normally at 11,025 Hz, and a
// duration in seconds equal to samples.size() / sample_rate. The implementation
// guards empty/very-low-rate input, but the binding owns stricter validation;
// it does not scan every sample or reconcile inconsistent duration metadata.
//
// Calls borrow the input vector only until they return and produce results that
// own all vector/string storage. Analysis is reentrant because every mutable
// value is call-local. It intentionally allocates and performs O(n) work, so it
// belongs on a worker thread and must never run in a real-time audio callback.

#pragma once

#include <string>
#include <vector>

namespace orchard {

// Times are seconds, confidence/probability values are nominally in [0, 1], and
// ordered event vectors use playback order unless stated otherwise.
struct EnergyPoint {
  double time = 0;
  // RMS relative to the track reference level, capped at 1.5.
  double energy = 0;
};

struct Phrase {
  double start = 0;
  double end = 0;
  std::string type;
  double confidence = 0;
};

struct TempoResult {
  double bpm = 0;
  // Seconds per beat; zero means that no defensible tempo was found.
  double beat_interval = 0;
  double first_beat = 0;
  double confidence = 0;
  std::vector<double> beats;
  std::vector<double> downbeats;
};

struct AnalysisResult {
  double duration = 0;
  double bpm = 0;
  double beat_interval = 0;
  double first_beat = 0;
  double beat_confidence = 0;
  std::vector<double> beats;
  std::vector<double> downbeats;
  std::vector<double> phrase_boundaries;
  std::vector<Phrase> phrases;
  std::string key;
  double key_confidence = 0;
  // Sum-normalized C through B pitch-class energy in chromatic order.
  std::vector<double> chroma;
  double audible_start_time = 0;
  double pickup_time = 0;
  double pickup_confidence = 0;
  double mix_in_time = 0;
  double mix_in_confidence = 0;
  double intro_end_time = 0;
  double outro_start_time = 0;
  double content_end_time = 0;
  double mix_out_time = 0;
  // RMS dBFS with a -0.691 offset, not gated/K-weighted integrated LUFS.
  double loudness_lufs = -70;
  double peak_dbfs = -70;
  double dynamic_range_db = 0;
  std::vector<EnergyPoint> energy_curve;
  double vocal_probability = 0;
};

/**
 * Extracts envelope, transition, tempo, key, spectral, and structure features.
 * Invalid top-level input returns the default result rather than throwing.
 * FFT frames, percentile copies, and result vectors are allocated within the
 * call; no native handles or background work survive its return.
 */
AnalysisResult AnalyzeAudio(
  const std::vector<float>& samples,
  double sample_rate,
  double supplied_duration
);

/**
 * Estimates tempo from at most the first 180 seconds, then extrapolates the
 * selected beat grid through `duration`. `audible_start` is used only to align
 * the first reported beat near meaningful content.
 */
TempoResult AnalyzeTempo(
  const std::vector<float>& samples,
  double sample_rate,
  double duration,
  double audible_start
);

}  // namespace orchard
