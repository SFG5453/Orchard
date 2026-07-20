#include "audio_analysis.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <numeric>
#include <string>
#include <utility>
#include <vector>

// Offline DSP implementation: reusable vectors and percentile copies favor
// readable whole-track analysis over real-time allocation constraints.

namespace orchard {
namespace {

constexpr double kPi = 3.14159265358979323846;

struct EnvelopeResult {
  double window_seconds = 0.25;
  double noise_floor = 0;
  double reference = 0;
  double threshold = 0;
  double audible_start = 0;
  double pickup_confidence = 0;
  double content_end = 0;
  std::vector<double> levels;
};

double Clamp(double value, double minimum, double maximum) {
  return std::max(minimum, std::min(maximum, value));
}

double ToDb(double value) {
  return value > 1e-9 ? 20.0 * std::log10(value) : -70.0;
}

double Percentile(std::vector<double> values, double ratio) {
  if (values.empty()) return 0;
  const size_t index = static_cast<size_t>(Clamp(ratio, 0, 1) * (values.size() - 1));
  std::nth_element(values.begin(), values.begin() + index, values.end());
  return values[index];
}

double Average(const std::vector<double>& values, size_t start, size_t end) {
  start = std::min(start, values.size());
  end = std::min(std::max(start, end), values.size());
  if (start == end) return 0;
  return std::accumulate(values.begin() + start, values.begin() + end, 0.0) / (end - start);
}

// Only a genuinely deep quiet passage followed by a sustained recovery is a
// comeback. Treating every later loud window as a ramp-up pushes ordinary,
// gently varying outros all the way to the file's end.
bool HasMaterialRecovery(
  const std::vector<double>& levels,
  size_t start,
  size_t sustain_windows,
  double reference,
  double quiet_level
) {
  if (reference <= 0 || quiet_level >= reference * 0.38) return false;
  const double threshold = std::max(reference * 0.72, quiet_level * 1.8);
  for (size_t index = start; index + sustain_windows <= levels.size(); ++index) {
    if (Average(levels, index, index + sustain_windows) >= threshold) return true;
  }
  return false;
}

bool HasQuietThenRecovery(
  const std::vector<double>& levels,
  size_t start,
  size_t sustain_windows,
  double reference
) {
  if (reference <= 0) return false;
  bool found_quiet = false;
  for (size_t index = start; index + sustain_windows <= levels.size(); ++index) {
    const double average = Average(levels, index, index + sustain_windows);
    if (average < reference * 0.38) found_quiet = true;
    else if (found_quiet && average >= reference * 0.72) return true;
  }
  return false;
}

// Unnormalized radix-2 Cooley-Tukey FFT. Internal callers must provide a
// non-empty power-of-two frame; the transform intentionally works in place.
void Fft(std::vector<std::complex<double>>& values) {
  const size_t size = values.size();
  for (size_t index = 1, swapped = 0; index < size; ++index) {
    size_t bit = size >> 1;
    for (; swapped & bit; bit >>= 1) swapped ^= bit;
    swapped ^= bit;
    if (index < swapped) std::swap(values[index], values[swapped]);
  }
  for (size_t length = 2; length <= size; length <<= 1) {
    const auto root = std::polar(1.0, -2.0 * kPi / length);
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

// Builds nominal 250 ms RMS windows. Percentile-derived reference/noise levels
// adapt absolute gates to the track; sustained activity locates the pickup,
// while a separate quiet-tail pass distinguishes content end from file duration.
EnvelopeResult AnalyzeEnvelope(
  const std::vector<float>& samples,
  double sample_rate,
  double duration
) {
  EnvelopeResult result;
  const size_t window_size = std::max<size_t>(1, sample_rate * result.window_seconds);
  for (size_t start = 0; start < samples.size(); start += window_size) {
    const size_t end = std::min(samples.size(), start + window_size);
    double sum = 0;
    for (size_t index = start; index < end; ++index) sum += samples[index] * samples[index];
    result.levels.push_back(std::sqrt(sum / std::max<size_t>(1, end - start)));
  }
  if (result.levels.empty()) return result;

  result.noise_floor = Percentile(result.levels, 0.05);
  result.reference = Percentile(result.levels, 0.85);
  result.threshold = std::max({
    0.0025,
    std::min(result.noise_floor * 2.6, result.reference * 0.28),
    result.reference * 0.1
  });
  const size_t sustain = std::max<size_t>(4, std::round(1.5 / result.window_seconds));
  for (size_t index = 0; index + sustain <= result.levels.size(); ++index) {
    size_t active = 0;
    double peak = 0;
    for (size_t cursor = index; cursor < index + sustain; ++cursor) {
      if (result.levels[cursor] >= result.threshold) ++active;
      peak = std::max(peak, result.levels[cursor]);
    }
    if (active < sustain * 2 / 3 || peak < result.threshold * 1.45) continue;
    result.audible_start = std::max(0.0, index * result.window_seconds - 0.1);
    const double local = Average(result.levels, index, index + sustain);
    result.pickup_confidence = Clamp(
      (local - result.noise_floor) / std::max(1e-6, result.reference - result.noise_floor),
      0,
      1
    );
    break;
  }

  result.content_end = duration;
  const double silence_threshold = std::max(
    0.0015,
    std::min(result.threshold * 0.25, result.reference * 0.04)
  );
  size_t quiet_start = result.levels.size();
  while (quiet_start > 0 && result.levels[quiet_start - 1] < silence_threshold) {
    --quiet_start;
  }
  const double trailing_silence = duration - quiet_start * result.window_seconds;
  if (trailing_silence >= 0.35) {
    result.content_end = std::max(0.0, quiet_start * result.window_seconds);
  } else {
    for (size_t end = result.levels.size(); end > sustain; --end) {
      const size_t start = end - sustain;
      size_t active = 0;
      for (size_t cursor = start; cursor < end; ++cursor) {
        if (result.levels[cursor] >= result.threshold) ++active;
      }
      if (active >= sustain / 2 && Average(result.levels, start, end) >= result.threshold * 0.85) {
        result.content_end = std::min(duration, end * result.window_seconds);
        break;
      }
    }
  }
  return result;
}

// Finds a late internal silence bordered by resumed audio, then backtracks to
// its energy cliff. Terminal silence remains the envelope's content-end cue.
double FindMixOutTime(
  const std::vector<float>& samples,
  double sample_rate,
  double duration,
  const EnvelopeResult& envelope
) {
  constexpr double window_seconds = 0.05;
  const size_t window_size = std::max<size_t>(1, sample_rate * window_seconds);
  std::vector<double> levels;
  for (size_t start = 0; start < samples.size(); start += window_size) {
    const size_t end = std::min(samples.size(), start + window_size);
    double sum = 0;
    for (size_t index = start; index < end; ++index) sum += samples[index] * samples[index];
    levels.push_back(std::sqrt(sum / std::max<size_t>(1, end - start)));
  }
  if (levels.empty()) return envelope.content_end;

  const double silence_threshold = std::max(
    0.0015,
    std::min(envelope.threshold * 0.25, envelope.reference * 0.04)
  );
  const size_t search_start = std::min(
    levels.size(),
    static_cast<size_t>(duration * 0.55 / window_seconds)
  );
  const size_t context_windows = static_cast<size_t>(2.0 / window_seconds);
  const size_t recovery_windows = std::max<size_t>(1, std::round(3.0 / window_seconds));
  size_t best_index = 0;
  double best_duration = 0;

  for (size_t index = search_start; index < levels.size();) {
    if (levels[index] >= silence_threshold) {
      ++index;
      continue;
    }
    size_t end = index + 1;
    while (end < levels.size() && levels[end] < silence_threshold) ++end;
    const double silence_duration = (end - index) * window_seconds;
    const double silence_end = end * window_seconds;
    if (silence_duration >= 0.3 && silence_end <= duration - 4.0) {
      const size_t before_start = index > context_windows ? index - context_windows : 0;
      const size_t after_end = std::min(levels.size(), end + context_windows);
      const double before_peak = *std::max_element(levels.begin() + before_start, levels.begin() + index);
      const double after_peak = *std::max_element(levels.begin() + end, levels.begin() + after_end);
      const double quiet_level = Average(levels, index, end);
      // Late gaps often separate an outro/hidden track and remain useful mix
      // points. Earlier gaps are protected when the main arrangement returns.
      const bool early_gap = index * window_seconds < envelope.content_end * 0.8;
      if (before_peak >= silence_threshold * 2 &&
          after_peak >= silence_threshold * 2 &&
          (!early_gap || !HasMaterialRecovery(
            levels,
            end,
            recovery_windows,
            envelope.reference,
            quiet_level
          )) &&
          silence_duration > best_duration) {
        best_index = index;
        best_duration = silence_duration;
      }
    }
    index = end;
  }
  if (!best_index) return envelope.content_end;

  const double cliff_threshold = std::max(silence_threshold * 2, envelope.reference * 0.65);
  const size_t maximum_backtrack = static_cast<size_t>(4.0 / window_seconds);
  size_t cliff_start = best_index;
  while (cliff_start > search_start && best_index - cliff_start < maximum_backtrack &&
         levels[cliff_start - 1] < cliff_threshold) {
    --cliff_start;
  }
  return cliff_start * window_seconds;
}

double NearestDownbeat(const std::vector<double>& downbeats, double target, double fallback) {
  if (downbeats.empty()) return fallback;
  auto found = std::lower_bound(downbeats.begin(), downbeats.end(), target);
  if (found == downbeats.begin()) return *found;
  if (found == downbeats.end()) return downbeats.back();
  return target - *(found - 1) <= *found - target ? *(found - 1) : *found;
}

double DownbeatAtOrBefore(const std::vector<double>& downbeats, double target, double fallback) {
  if (downbeats.empty()) return fallback;
  auto found = std::upper_bound(downbeats.begin(), downbeats.end(), target);
  return found == downbeats.begin() ? downbeats.front() : *(found - 1);
}

// Sparse 4096-point Hann frames feed chroma templates and broad spectral bands.
// FFT power is log-compressed before aggregation; chroma is sum-normalized and
// the vocal value is a bounded spectral heuristic rather than a classifier.
void AnalyzeKeyAndTimbre(
  const std::vector<float>& samples,
  double sample_rate,
  double start_time,
  double end_time,
  AnalysisResult& result
) {
  constexpr size_t frame_size = 4096;
  const size_t hop_size = std::max<size_t>(frame_size, sample_rate * 0.65);
  const size_t first_sample = std::min(samples.size(), static_cast<size_t>(start_time * sample_rate));
  const size_t final_sample = std::min(samples.size(), static_cast<size_t>(end_time * sample_rate));
  std::array<double, 12> chroma{};
  std::vector<std::complex<double>> spectrum(frame_size);
  double chroma_weight = 0;
  double low_energy = 0;
  double vocal_energy = 0;
  double high_energy = 0;
  double flatness_total = 0;
  size_t accepted_frames = 0;

  for (size_t start = first_sample; start + frame_size <= final_sample; start += hop_size) {
    double square_sum = 0;
    for (size_t index = 0; index < frame_size; ++index) {
      const double value = samples[start + index];
      square_sum += value * value;
      const double window = 0.5 - 0.5 * std::cos(2.0 * kPi * index / (frame_size - 1));
      spectrum[index] = std::complex<double>(value * window, 0);
    }
    const double rms = std::sqrt(square_sum / frame_size);
    if (rms < 0.0025) continue;
    Fft(spectrum);

    double frame_chroma = 0;
    double log_sum = 0;
    double arithmetic_sum = 0;
    size_t flatness_bins = 0;
    for (size_t bin = 1; bin < frame_size / 2; ++bin) {
      const double frequency = bin * sample_rate / frame_size;
      if (frequency < 45 || frequency > std::min(5000.0, sample_rate * 0.48)) continue;
      const double power = std::norm(spectrum[bin]);
      const double perceptual_power = std::log1p(power);
      if (frequency < 250) low_energy += perceptual_power;
      else if (frequency <= 4000) {
        vocal_energy += perceptual_power;
        log_sum += std::log(std::max(1e-12, power));
        arithmetic_sum += power;
        ++flatness_bins;
      } else high_energy += perceptual_power;
      if (frequency > 5000) continue;
      const int midi = static_cast<int>(std::round(69.0 + 12.0 * std::log2(frequency / 440.0)));
      const int pitch_class = (midi % 12 + 12) % 12;
      const double weight = std::log1p(power);
      chroma[pitch_class] += weight * rms;
      frame_chroma += weight;
    }
    if (flatness_bins && arithmetic_sum > 0) {
      flatness_total += std::exp(log_sum / flatness_bins) / (arithmetic_sum / flatness_bins);
    }
    chroma_weight += std::max(1e-9, frame_chroma * rms);
    ++accepted_frames;
  }

  result.chroma.assign(chroma.begin(), chroma.end());
  const double chroma_sum = std::accumulate(result.chroma.begin(), result.chroma.end(), 0.0);
  if (chroma_sum > 0) for (double& value : result.chroma) value /= chroma_sum;

  constexpr std::array<double, 12> major = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
  constexpr std::array<double, 12> minor = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
  // Of course APPLE would cause me to do some bs like this for it to compile.
  constexpr std::array<const char*, 12> names = {
    "C", "C\xE2\x99\xAF", "D", "E\xE2\x99\xAD", "E", "F",
    "F\xE2\x99\xAF", "G", "A\xE2\x99\xAD", "A", "B\xE2\x99\xAD", "B"
  };
  std::vector<std::pair<double, std::string>> candidates;
  for (size_t root = 0; root < 12; ++root) {
    double major_score = 0;
    double minor_score = 0;
    for (size_t pitch = 0; pitch < 12; ++pitch) {
      major_score += result.chroma[pitch] * major[(pitch + 12 - root) % 12];
      minor_score += result.chroma[pitch] * minor[(pitch + 12 - root) % 12];
    }
    candidates.emplace_back(major_score, std::string(names[root]) + " major");
    candidates.emplace_back(minor_score, std::string(names[root]) + " minor");
  }
  std::sort(candidates.begin(), candidates.end(), std::greater<>());
  if (chroma_weight > 0 && !candidates.empty()) {
    result.key = candidates[0].second;
    result.key_confidence = Clamp(
      (candidates[0].first - candidates[1].first) / std::max(0.01, candidates[0].first) * 4.0,
      0,
      1
    );
  }

  const double total_spectral = low_energy + vocal_energy + high_energy;
  const double mid_ratio = vocal_energy / std::max(1e-12, total_spectral);
  const double low_ratio = low_energy / std::max(1e-12, total_spectral);
  const double flatness = flatness_total / std::max<size_t>(1, accepted_frames);
  // Compress spectral power before comparing bands. Raw FFT power lets kick
  // drums and bass overwhelm the much wider voice band, which made vocal-led
  // tracks look instrumental. Flatness adds a small boost for speech-like
  // broadband detail without making it the primary signal.
  const double vocal_score = -2.4 + 5.2 * mid_ratio - 0.8 * low_ratio + 0.6 * flatness;
  result.vocal_probability = Clamp(1.0 / (1.0 + std::exp(-vocal_score)), 0, 1);
}

// Models 4/4 music in eight-bar (32-beat) phrases and snaps transition cues to
// the inferred downbeat grid; energy is used only for boundary/type refinement.
void BuildStructure(const EnvelopeResult& envelope, AnalysisResult& result) {
  const double phrase_seconds = result.beat_interval > 0 ? result.beat_interval * 32.0 : 16.0;
  const double phrase_start = !result.downbeats.empty()
    ? result.downbeats.front()
    : envelope.audible_start;
  const size_t first_window = static_cast<size_t>(envelope.audible_start / envelope.window_seconds);
  const size_t four_seconds = std::max<size_t>(1, 4.0 / envelope.window_seconds);
  const size_t quiet_windows = std::max<size_t>(1, std::round(3.0 / envelope.window_seconds));
  const size_t recovery_windows = std::max<size_t>(1, std::round(3.0 / envelope.window_seconds));
  size_t strong_window = first_window;
  for (size_t index = first_window; index + four_seconds <= envelope.levels.size(); ++index) {
    if (Average(envelope.levels, index, index + four_seconds) >= envelope.reference * 0.62) {
      strong_window = index;
      break;
    }
  }
  const double raw_intro = std::max(
    phrase_start + phrase_seconds,
    strong_window * envelope.window_seconds
  );
  result.intro_end_time = Clamp(
    NearestDownbeat(result.downbeats, raw_intro, raw_intro),
    envelope.audible_start,
    std::min(envelope.content_end, 48.0)
  );

  double raw_outro = std::max(result.intro_end_time, envelope.content_end - phrase_seconds);
  const size_t search_start = static_cast<size_t>(
    std::max(result.intro_end_time, envelope.content_end * 0.6) / envelope.window_seconds
  );
  for (size_t index = search_start; index + four_seconds < envelope.levels.size(); ++index) {
    const double section_average = Average(envelope.levels, index, index + four_seconds);
    const double tail_average = Average(envelope.levels, index, envelope.levels.size());
    if (section_average >= envelope.reference * 0.68 ||
        tail_average >= envelope.reference * 0.72) {
      continue;
    }
    // A candidate may begin just before a breakdown because its four-second
    // average straddles the energy drop. Look for the complete quiet/recovery
    // sequence instead of judging only the candidate's first window.
    if (!HasQuietThenRecovery(
      envelope.levels,
      index,
      std::max(quiet_windows, recovery_windows),
      envelope.reference
    )) {
      raw_outro = index * envelope.window_seconds;
      break;
    }
  }
  result.outro_start_time = Clamp(
    NearestDownbeat(result.downbeats, raw_outro, raw_outro),
    result.intro_end_time,
    envelope.content_end
  );

  result.phrase_boundaries.push_back(phrase_start);
  for (double time = phrase_start + phrase_seconds; time < envelope.content_end; time += phrase_seconds) {
    result.phrase_boundaries.push_back(time);
  }
  result.phrase_boundaries.push_back(result.intro_end_time);
  result.phrase_boundaries.push_back(result.outro_start_time);
  if (result.phrase_boundaries.empty() || result.phrase_boundaries.back() < envelope.content_end - 0.05) {
    result.phrase_boundaries.push_back(envelope.content_end);
  }
  std::sort(result.phrase_boundaries.begin(), result.phrase_boundaries.end());
  result.phrase_boundaries.erase(
    std::unique(
      result.phrase_boundaries.begin(),
      result.phrase_boundaries.end(),
      [](double left, double right) { return std::abs(left - right) < 0.05; }
    ),
    result.phrase_boundaries.end()
  );
  for (size_t index = 0; index + 1 < result.phrase_boundaries.size(); ++index) {
    const double start = result.phrase_boundaries[index];
    const double end = result.phrase_boundaries[index + 1];
    const size_t energy_start = static_cast<size_t>(start / envelope.window_seconds);
    const size_t energy_end = static_cast<size_t>(std::ceil(end / envelope.window_seconds));
    const double energy = Average(envelope.levels, energy_start, energy_end);
    std::string type = "body";
    if (end <= result.intro_end_time + 0.1) type = "intro";
    else if (start >= result.outro_start_time - 0.1) type = "outro";
    else if (energy < envelope.reference * 0.58) type = "breakdown";
    result.phrases.push_back({start, end, type, Clamp(energy / std::max(1e-6, envelope.reference), 0, 1)});
  }

  const double eight_bar_target = result.beat_interval > 0
    ? phrase_start + result.beat_interval * 32.0
    : result.intro_end_time;
  const double latest_cue = std::max(
    envelope.audible_start,
    std::min(36.0, envelope.content_end * 0.28)
  );
  // The first eight-bar boundary is the useful dominance target. A later
  // energy-based intro boundary can describe the song structure, but seeking
  // to it discards a musical intro that can be pre-rolled under the outgoing
  // track.
  const double bounded_target = std::min(latest_cue, eight_bar_target);
  result.mix_in_time = Clamp(
    DownbeatAtOrBefore(result.downbeats, bounded_target, bounded_target),
    envelope.audible_start,
    latest_cue
  );
  const size_t cue_window = static_cast<size_t>(result.mix_in_time / envelope.window_seconds);
  const double cue_energy = Average(envelope.levels, cue_window, cue_window + four_seconds);
  result.mix_in_confidence = Clamp(
    result.beat_confidence * 0.65 +
      Clamp(cue_energy / std::max(1e-6, envelope.reference), 0, 1) * 0.35,
    0,
    1
  );
}

}  // namespace

// Orchestrates independent envelope, tempo, level, spectral, and structure
// stages. Every temporary and returned allocation is owned by this call.
AnalysisResult AnalyzeAudio(
  const std::vector<float>& samples,
  double sample_rate,
  double supplied_duration
) {
  AnalysisResult result;
  result.duration = supplied_duration > 0 ? supplied_duration : samples.size() / sample_rate;
  if (samples.empty() || sample_rate < 1000 || result.duration <= 0) return result;
  const auto envelope = AnalyzeEnvelope(samples, sample_rate, result.duration);
  result.audible_start_time = envelope.audible_start;
  result.pickup_time = envelope.audible_start;
  result.pickup_confidence = envelope.pickup_confidence;
  result.content_end_time = envelope.content_end;
  result.mix_out_time = FindMixOutTime(samples, sample_rate, result.duration, envelope);

  const auto tempo = AnalyzeTempo(samples, sample_rate, result.duration, envelope.audible_start);
  result.bpm = tempo.bpm;
  result.beat_interval = tempo.beat_interval;
  result.first_beat = tempo.first_beat;
  result.beat_confidence = tempo.confidence;
  result.beats = tempo.beats;
  result.downbeats = tempo.downbeats;

  // This level estimate is RMS dBFS minus the conventional 0.691 offset. It is
  // intentionally not advertised as a gated, K-weighted loudness measurement;
  // the envelope percentile spread supplies the companion dynamics estimate.
  double square_sum = 0;
  double peak = 0;
  const size_t content_start = std::min(samples.size(), static_cast<size_t>(envelope.audible_start * sample_rate));
  const size_t content_end = std::min(samples.size(), static_cast<size_t>(envelope.content_end * sample_rate));
  for (size_t index = content_start; index < content_end; ++index) {
    square_sum += samples[index] * samples[index];
    peak = std::max(peak, std::abs(static_cast<double>(samples[index])));
  }
  const double rms = std::sqrt(square_sum / std::max<size_t>(1, content_end - content_start));
  result.loudness_lufs = std::max(-70.0, ToDb(rms) - 0.691);
  result.peak_dbfs = ToDb(peak);
  result.dynamic_range_db = Clamp(
    ToDb(Percentile(envelope.levels, 0.95)) - ToDb(Percentile(envelope.levels, 0.2)),
    0,
    70
  );

  // Downsample to at most 240 points and scale against the track reference;
  // values may exceed unity for loud passages but are capped at 1.5.
  const size_t curve_stride = std::max<size_t>(1, (envelope.levels.size() + 239) / 240);
  for (size_t index = 0; index < envelope.levels.size(); index += curve_stride) {
    result.energy_curve.push_back({
      index * envelope.window_seconds,
      Clamp(envelope.levels[index] / std::max(1e-6, envelope.reference), 0, 1.5)
    });
  }
  AnalyzeKeyAndTimbre(samples, sample_rate, envelope.audible_start, envelope.content_end, result);
  BuildStructure(envelope, result);
  return result;
}

}  // namespace orchard
