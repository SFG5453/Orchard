#include "wsola.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>

namespace orchard {
namespace {

// MSVC only defines M_PI when _USE_MATH_DEFINES is set before <cmath>, and the
// binding is built on Windows too, so the constant is spelled out here.
constexpr float kPi = 3.14159265358979323846f;

// 46 ms at 44.1 kHz. Long enough that a 40 Hz bass period fits inside the
// overlap, short enough that transients do not smear across a beat.
constexpr double kFrameSeconds = 0.046;

// Guards the normalized cross-correlation denominator on near-silent frames,
// where the numerator is noise and any offset would otherwise "win".
constexpr float kCorrelationEpsilon = 1e-9f;

int ResolveFrameSize(const WsolaConfig& config, double sample_rate) {
  if (config.frame_size > 0) return config.frame_size;
  const auto derived = static_cast<int>(std::lround(sample_rate * kFrameSeconds));
  // Force an even frame so the synthesis hop is exactly half and the Hann
  // window satisfies constant overlap-add.
  return std::max(4, derived - (derived % 2));
}

std::vector<float> Downmix(const std::vector<std::vector<float>>& channels, size_t length) {
  std::vector<float> mono(length, 0.0f);
  if (channels.empty()) return mono;
  const auto scale = 1.0f / static_cast<float>(channels.size());
  for (const auto& channel : channels) {
    for (size_t index = 0; index < length; ++index) {
      mono[index] += channel[index] * scale;
    }
  }
  return mono;
}

std::vector<float> HannWindow(int size) {
  std::vector<float> window(static_cast<size_t>(size));
  for (int index = 0; index < size; ++index) {
    window[static_cast<size_t>(index)] =
      0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(index) /
                             static_cast<float>(size));
  }
  return window;
}

// Normalized cross-correlation between the candidate segment at `start` and
// `reference`. Normalizing by the candidate's own energy stops loud passages
// from always outscoring the genuinely better-aligned quiet ones.
float Similarity(const std::vector<float>& mono, int64_t start,
                 const std::vector<float>& reference) {
  float dot = 0.0f;
  float energy = 0.0f;
  for (size_t index = 0; index < reference.size(); ++index) {
    const auto value = mono[static_cast<size_t>(start) + index];
    dot += value * reference[index];
    energy += value * value;
  }
  return dot / std::sqrt(energy + kCorrelationEpsilon);
}

}  // namespace

std::vector<std::vector<float>> WsolaStretch(
  const std::vector<std::vector<float>>& channels,
  double sample_rate,
  const WsolaConfig& config
) {
  if (channels.empty() || !std::isfinite(sample_rate) || sample_rate < 1000) return {};
  if (!std::isfinite(config.ratio) || config.ratio <= 0) return {};

  const auto input_length = channels.front().size();
  if (input_length == 0) return {};
  for (const auto& channel : channels) {
    if (channel.size() != input_length) return {};
  }

  const int frame = ResolveFrameSize(config, sample_rate);
  const int synthesis_hop = frame / 2;
  const int overlap = frame - synthesis_hop;
  const int radius = config.search_radius > 0 ? config.search_radius : frame / 4;

  const bool gliding =
    config.start_ratio > 0 && config.glide > 0 && std::isfinite(config.start_ratio);
  const double glide_span = gliding ? std::min(1.0, config.glide) : 0.0;
  const double opening_ratio = gliding ? config.start_ratio : config.ratio;
  if (input_length < static_cast<size_t>(frame)) return {};

  const auto window = HannWindow(frame);
  const auto mono = Downmix(channels, input_length);

  // Allocated against the slowest ratio in play so a glide cannot overrun the
  // buffer; the unused tail is trimmed once the window accumulator is known.
  const auto output_length =
    static_cast<size_t>(std::llround(static_cast<double>(input_length) *
                                     std::max(config.ratio, opening_ratio))) +
    static_cast<size_t>(frame);
  std::vector<std::vector<float>> output(channels.size(),
                                         std::vector<float>(output_length, 0.0f));
  // Hann at 50% overlap sums to unity in steady state but not across the first
  // and last frames, so the accumulated window is divided out at the end
  // rather than assumed.
  std::vector<float> window_sum(output_length, 0.0f);

  std::vector<float> reference(static_cast<size_t>(overlap), 0.0f);
  bool have_reference = false;
  // Advanced by a per-frame hop rather than derived from the frame index, so a
  // glide can vary the rate at which input is consumed.
  double analysis_position = 0;

  for (size_t frame_index = 0;; ++frame_index) {
    const auto nominal = static_cast<int64_t>(std::llround(analysis_position));
    const auto synthesis_start =
      static_cast<int64_t>(frame_index) * static_cast<int64_t>(synthesis_hop);
    if (synthesis_start + frame > static_cast<int64_t>(output_length)) break;

    // The glide is scheduled against input consumed, which is monotonic and
    // known exactly, unlike the output length under a varying ratio.
    const double consumed = analysis_position / static_cast<double>(input_length);
    const double active_ratio =
      gliding && consumed < glide_span
        ? opening_ratio + (config.ratio - opening_ratio) * (consumed / glide_span)
        : config.ratio;
    // The analysis hop is what actually realizes the ratio: output advances by
    // synthesis_hop per frame while input advances by analysis_hop.
    const double analysis_hop = std::max(1.0, synthesis_hop / active_ratio);

    int64_t chosen = nominal;
    if (have_reference) {
      // Slide the analysis window within +/-radius to find the placement whose
      // leading overlap best continues the waveform already written out. This
      // is the whole point of WSOLA: it removes the phase discontinuity that
      // plain OLA resampling would leave at every frame boundary.
      const auto lowest = std::max<int64_t>(0, nominal - radius);
      const auto highest = std::min<int64_t>(
        nominal + radius,
        static_cast<int64_t>(input_length) - frame
      );
      float best_score = -std::numeric_limits<float>::infinity();
      for (auto candidate = lowest; candidate <= highest; ++candidate) {
        const auto score = Similarity(mono, candidate, reference);
        if (score > best_score) {
          best_score = score;
          chosen = candidate;
        }
      }
    }
    if (chosen < 0 || chosen + frame > static_cast<int64_t>(input_length)) break;

    for (size_t channel = 0; channel < channels.size(); ++channel) {
      const auto& source = channels[channel];
      auto& destination = output[channel];
      for (int index = 0; index < frame; ++index) {
        destination[static_cast<size_t>(synthesis_start) + index] +=
          source[static_cast<size_t>(chosen) + index] * window[static_cast<size_t>(index)];
      }
    }
    for (int index = 0; index < frame; ++index) {
      window_sum[static_cast<size_t>(synthesis_start) + index] +=
        window[static_cast<size_t>(index)];
    }

    // The natural continuation of what was just emitted: one synthesis hop on
    // from the chosen offset. The next frame is scored against this.
    const auto continuation = chosen + synthesis_hop;
    if (continuation + overlap > static_cast<int64_t>(input_length)) break;
    for (int index = 0; index < overlap; ++index) {
      reference[static_cast<size_t>(index)] =
        mono[static_cast<size_t>(continuation) + index];
    }
    have_reference = true;
    // The chosen offset is a local perturbation only; the nominal grid keeps
    // advancing on schedule so search jitter cannot accumulate into drift.
    analysis_position += analysis_hop;
  }

  // Trim to the last sample that actually received window energy so callers do
  // not inherit the frame of zero padding the accumulator reserved.
  size_t written = 0;
  for (size_t index = 0; index < output_length; ++index) {
    if (window_sum[index] > 1e-6f) written = index + 1;
  }
  for (auto& channel : output) {
    for (size_t index = 0; index < written; ++index) {
      if (window_sum[index] > 1e-6f) channel[index] /= window_sum[index];
    }
    channel.resize(written);
  }
  return output;
}

}  // namespace orchard
