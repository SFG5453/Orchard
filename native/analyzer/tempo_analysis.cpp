// Offline tempo and beat-grid estimation. All spectra, envelopes, scores, and
// event vectors are call-owned allocations; this file has no synchronization or
// real-time guarantees and must run on the binding's worker-pool thread.

#include "audio_analysis.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <numeric>
#include <vector>

namespace orchard {
namespace {

constexpr double kPi = 3.14159265358979323846;

double Clamp(double value, double minimum, double maximum) {
  return std::max(minimum, std::min(maximum, value));
}

// Unnormalized in-place radix-2 FFT. The fixed 512-sample caller satisfies the
// non-empty power-of-two precondition, so no generic padding is performed here.
void Fft(std::vector<std::complex<double>>& values) {
  const size_t size = values.size();
  for (size_t index = 1, swapped = 0; index < size; ++index) {
    size_t bit = size >> 1;
    for (; swapped & bit; bit >>= 1) swapped ^= bit;
    swapped ^= bit;
    if (index < swapped) std::swap(values[index], values[swapped]);
  }

  for (size_t length = 2; length <= size; length <<= 1) {
    const std::complex<double> root = std::polar(1.0, -2.0 * kPi / length);
    for (size_t start = 0; start < size; start += length) {
      std::complex<double> weight(1, 0);
      for (size_t offset = 0; offset < length / 2; ++offset) {
        const auto even = values[start + offset];
        const auto odd = values[start + offset + length / 2] * weight;
        values[start + offset] = even + odd;
        values[start + offset + length / 2] = even - odd;
        weight *= root;
      }
    }
  }
}

// Converts at most the first 180 seconds into a spectral-flux onset envelope.
// Each Hann-windowed spectrum contributes only positive log-magnitude changes;
// subtracting 1.08 times a roughly +/-350 ms local mean suppresses steady-state
// energy, then peak normalization plus sqrt expands quieter remaining onsets.
std::vector<double> OnsetEnvelope(
  const std::vector<float>& samples,
  double sample_rate,
  size_t frame_size,
  size_t hop_size
) {
  const size_t maximum_samples = std::min(
    samples.size(),
    static_cast<size_t>(sample_rate * 180.0)
  );
  if (maximum_samples < frame_size) return {};

  const size_t frame_count = 1 + (maximum_samples - frame_size) / hop_size;
  std::vector<double> envelope(frame_count, 0);
  std::vector<double> previous(frame_size / 2, 0);
  std::vector<std::complex<double>> spectrum(frame_size);

  for (size_t frame = 0; frame < frame_count; ++frame) {
    const size_t start = frame * hop_size;
    for (size_t index = 0; index < frame_size; ++index) {
      const double window = 0.5 - 0.5 * std::cos(2.0 * kPi * index / (frame_size - 1));
      spectrum[index] = std::complex<double>(samples[start + index] * window, 0);
    }
    Fft(spectrum);

    double flux = 0;
    for (size_t bin = 1; bin < frame_size / 2; ++bin) {
      const double magnitude = std::log1p(std::abs(spectrum[bin]));
      flux += std::max(0.0, magnitude - previous[bin]);
      previous[bin] = magnitude;
    }
    envelope[frame] = flux;
  }

  const size_t radius = std::max<size_t>(2, static_cast<size_t>(sample_rate / hop_size * 0.35));
  std::vector<double> prefix(envelope.size() + 1, 0);
  for (size_t index = 0; index < envelope.size(); ++index) {
    prefix[index + 1] = prefix[index] + envelope[index];
  }
  for (size_t index = 0; index < envelope.size(); ++index) {
    const size_t left = index > radius ? index - radius : 0;
    const size_t right = std::min(envelope.size(), index + radius + 1);
    const double local_mean = (prefix[right] - prefix[left]) / std::max<size_t>(1, right - left);
    envelope[index] = std::max(0.0, envelope[index] - local_mean * 1.08);
  }

  const double peak = *std::max_element(envelope.begin(), envelope.end());
  if (peak > 0) {
    for (double& value : envelope) value = std::sqrt(value / peak);
  }
  return envelope;
}

// Energy-normalized autocorrelation: sum(x[n]x[n-lag]) divided by the geometric
// mean of both lagged energies. The epsilon keeps silent input finite.
double Correlation(const std::vector<double>& values, int lag) {
  if (lag <= 0 || static_cast<size_t>(lag) >= values.size()) return 0;
  double cross = 0;
  double left_energy = 0;
  double right_energy = 0;
  for (size_t index = lag; index < values.size(); ++index) {
    const double left = values[index];
    const double right = values[index - lag];
    cross += left * right;
    left_energy += left * left;
    right_energy += right * right;
  }
  return cross / std::sqrt(std::max(1e-12, left_energy * right_energy));
}

// Linear interpolation lets sub-frame lag refinement participate in phase
// scoring without resampling the complete onset envelope.
double SampleEnvelope(const std::vector<double>& values, double position) {
  if (position < 0 || position >= values.size() - 1) return 0;
  const size_t left = static_cast<size_t>(position);
  const double fraction = position - left;
  return values[left] * (1.0 - fraction) + values[left + 1] * fraction;
}

}  // namespace

// Searches 70-200 BPM. A candidate combines normalized correlation at its lag,
// 0.42 times the double-lag correlation, and a small Gaussian prior around 118
// BPM. Quadratic interpolation refines the winning lag by at most half a frame;
// phase maximizes onset strength, and the four-beat downbeat offset is the
// strongest phase among the first 256 beats. Confidence blends tempo strength,
// phase strength, and separation from non-neighboring candidates.
TempoResult AnalyzeTempo(
  const std::vector<float>& samples,
  double sample_rate,
  double duration,
  double audible_start
) {
  TempoResult result;
  // At the normal 11,025 Hz analysis rate this is a 46 ms Hann window with an
  // 11.6 ms hop. Other accepted sample rates retain the same sample counts.
  constexpr size_t frame_size = 512;
  constexpr size_t hop_size = 128;
  const auto envelope = OnsetEnvelope(samples, sample_rate, frame_size, hop_size);
  // Short or silent-enough inputs fail closed to the default zero tempo.
  if (envelope.size() < 64) return result;

  const double frames_per_second = sample_rate / hop_size;
  const int minimum_lag = std::max(2, static_cast<int>(std::floor(frames_per_second * 60.0 / 200.0)));
  const int maximum_lag = static_cast<int>(std::ceil(frames_per_second * 60.0 / 70.0));
  std::vector<double> scores(maximum_lag + 1, 0);
  int best_lag = minimum_lag;
  for (int lag = minimum_lag; lag <= maximum_lag; ++lag) {
    const double bpm = frames_per_second * 60.0 / lag;
    const double tempo_prior = std::exp(-std::pow((bpm - 118.0) / 75.0, 2.0));
    scores[lag] = Correlation(envelope, lag) +
      0.42 * Correlation(envelope, lag * 2) +
      0.08 * tempo_prior;
    if (scores[lag] > scores[best_lag]) best_lag = lag;
  }

  double refined_lag = best_lag;
  if (best_lag > minimum_lag && best_lag < maximum_lag) {
    const double left = scores[best_lag - 1];
    const double center = scores[best_lag];
    const double right = scores[best_lag + 1];
    const double denominator = left - 2.0 * center + right;
    if (std::abs(denominator) > 1e-9) {
      refined_lag += Clamp(0.5 * (left - right) / denominator, -0.5, 0.5);
    }
  }

  result.bpm = frames_per_second * 60.0 / refined_lag;
  result.beat_interval = 60.0 / result.bpm;
  const int phase_count = std::max(1, static_cast<int>(std::round(refined_lag)));
  double best_phase_score = -1;
  int best_phase = 0;
  for (int phase = 0; phase < phase_count; ++phase) {
    double score = 0;
    int count = 0;
    for (double position = phase; position < envelope.size(); position += refined_lag) {
      score += SampleEnvelope(envelope, position);
      ++count;
    }
    score /= std::max(1, count);
    if (score > best_phase_score) {
      best_phase_score = score;
      best_phase = phase;
    }
  }

  double first = best_phase / frames_per_second;
  while (first + result.beat_interval < audible_start - 0.15) first += result.beat_interval;
  while (first > audible_start + result.beat_interval) first -= result.beat_interval;
  result.first_beat = std::max(0.0, first);
  for (double time = result.first_beat; time <= duration + 1e-6; time += result.beat_interval) {
    result.beats.push_back(time);
  }

  int downbeat_offset = 0;
  double downbeat_score = -1;
  for (int offset = 0; offset < 4; ++offset) {
    double score = 0;
    int count = 0;
    for (size_t beat = offset; beat < result.beats.size() && beat < 256; beat += 4) {
      const double position = result.beats[beat] * frames_per_second;
      score += SampleEnvelope(envelope, position);
      ++count;
    }
    score /= std::max(1, count);
    if (score > downbeat_score) {
      downbeat_score = score;
      downbeat_offset = offset;
    }
  }
  for (size_t beat = downbeat_offset; beat < result.beats.size(); beat += 4) {
    result.downbeats.push_back(result.beats[beat]);
  }

  double runner_up = 0;
  for (int lag = minimum_lag; lag <= maximum_lag; ++lag) {
    if (std::abs(lag - best_lag) > 2) runner_up = std::max(runner_up, scores[lag]);
  }
  const double separation = (scores[best_lag] - runner_up) / std::max(0.05, scores[best_lag]);
  result.confidence = Clamp(
    0.35 * scores[best_lag] + 0.35 * best_phase_score + 0.3 * std::max(0.0, separation),
    0.0,
    1.0
  );
  if (!std::isfinite(result.bpm) || result.bpm < 60 || result.bpm > 220) return TempoResult{};
  return result;
}

}  // namespace orchard
