#include "transition_render.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "wsola.h"

namespace orchard {
namespace {

constexpr float kPi = 3.14159265358979323846f;

// Outside this range the input is not a tempo and the pairing is refused
// rather than silently rendered onto a nonsense grid.
constexpr double kMinBpm = 40;
constexpr double kMaxBpm = 220;

struct Biquad {
  float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
};

// RBJ cookbook second-order Butterworth high-pass, normalized by a0.
Biquad HighPass(double cutoff, double sample_rate) {
  const double w0 = 2.0 * kPi * cutoff / sample_rate;
  const double cosine = std::cos(w0);
  const double alpha = std::sin(w0) / (2.0 * 0.70710678);
  const double a0 = 1.0 + alpha;
  Biquad filter;
  filter.b0 = static_cast<float>(((1.0 + cosine) / 2.0) / a0);
  filter.b1 = static_cast<float>((-(1.0 + cosine)) / a0);
  filter.b2 = filter.b0;
  filter.a1 = static_cast<float>((-2.0 * cosine) / a0);
  filter.a2 = static_cast<float>((1.0 - alpha) / a0);
  return filter;
}

// Direct form I, run forward only. The resulting phase shift is identical on
// every channel and on both tracks, so it does not smear the stereo image or
// misalign the two sides of the mix against each other.
std::vector<float> ApplyHighPass(const std::vector<float>& input, const Biquad& filter) {
  std::vector<float> output(input.size(), 0.0f);
  float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (size_t index = 0; index < input.size(); ++index) {
    const float x0 = input[index];
    const float y0 =
      filter.b0 * x0 + filter.b1 * x1 + filter.b2 * x2 - filter.a1 * y1 - filter.a2 * y2;
    output[index] = y0;
    x2 = x1;
    x1 = x0;
    y2 = y1;
    y1 = y0;
  }
  return output;
}

TransitionResult Refuse(const std::string& reason) {
  TransitionResult result;
  result.rendered = false;
  result.rejected = reason;
  return result;
}

bool Ragged(const std::vector<std::vector<float>>& channels) {
  if (channels.empty() || channels.front().empty()) return true;
  const auto length = channels.front().size();
  for (const auto& channel : channels) {
    if (channel.size() != length) return true;
  }
  return false;
}

// Smoothstep, so the low-end handover eases in and out instead of cornering.
float Smooth(double progress) {
  const auto clamped = static_cast<float>(std::clamp(progress, 0.0, 1.0));
  return clamped * clamped * (3.0f - 2.0f * clamped);
}

}  // namespace

TransitionResult RenderTransition(
  const TransitionSource& outgoing,
  const TransitionSource& incoming,
  const TransitionConfig& config
) {
  if (!std::isfinite(config.sample_rate) || config.sample_rate < 1000) {
    return Refuse("invalid sample rate");
  }
  if (Ragged(outgoing.channels) || Ragged(incoming.channels)) {
    return Refuse("empty or ragged input channels");
  }
  if (outgoing.channels.size() != incoming.channels.size()) {
    return Refuse("channel count mismatch");
  }
  if (!std::isfinite(outgoing.bpm) || outgoing.bpm < kMinBpm || outgoing.bpm > kMaxBpm ||
      !std::isfinite(incoming.bpm) || incoming.bpm < kMinBpm || incoming.bpm > kMaxBpm) {
    return Refuse("missing or implausible tempo");
  }
  if (!std::isfinite(config.beats) || config.beats <= 0) {
    return Refuse("invalid overlap length");
  }

  // The overlap runs on the incoming track's grid, so the outgoing track is
  // the one that moves. WsolaConfig::ratio is output-over-input length, so
  // reaching a faster grid means compressing: a 120 BPM outgoing onto a 126
  // BPM grid is 120/126, below 1. Inverting this silently detunes the mix by
  // twice the tempo gap in the wrong direction.
  const double ratio = outgoing.bpm / incoming.bpm;
  if (std::abs(ratio - 1.0) > kMaxTransparentRatioDeviation) {
    return Refuse("tempo difference beyond transparent stretch range");
  }

  const double beat_seconds = 60.0 / incoming.bpm;
  const auto overlap_samples =
    static_cast<size_t>(std::llround(config.beats * beat_seconds * config.sample_rate));
  if (overlap_samples == 0) return Refuse("overlap rounds to zero samples");

  // The outgoing anchor is expressed on its own timeline, so it moves with the
  // stretch; the incoming anchor is already on the output timeline.
  const auto incoming_start =
    static_cast<int64_t>(std::llround(incoming.anchor * config.sample_rate));
  if (incoming_start < 0 ||
      static_cast<size_t>(incoming_start) + overlap_samples > incoming.channels.front().size()) {
    return Refuse("incoming track too short for the requested overlap");
  }

  WsolaConfig stretch;
  stretch.ratio = ratio;
  if (config.tempo_glide > 0 && std::abs(ratio - 1.0) > 1e-6) {
    // Slide onto the new tempo across the opening of the overlap instead of
    // stepping onto it, the way a DJ rides a pitch fader.
    stretch.start_ratio = 1.0;
    stretch.glide = std::min(1.0, config.tempo_glide);
  }
  const auto stretched = WsolaStretch(outgoing.channels, config.sample_rate, stretch);
  if (stretched.empty() || stretched.front().empty()) {
    return Refuse("outgoing track too short to time-scale");
  }

  const auto outgoing_start =
    static_cast<int64_t>(std::llround(outgoing.anchor * ratio * config.sample_rate));
  if (outgoing_start < 0 ||
      static_cast<size_t>(outgoing_start) + overlap_samples > stretched.front().size()) {
    return Refuse("outgoing track too short for the requested overlap");
  }

  const auto channel_count = incoming.channels.size();
  const auto crossover = HighPass(
    std::clamp(config.bass_crossover_hz, 40.0, 500.0),
    config.sample_rate
  );
  const auto swap_point = std::clamp(config.bass_swap, 0.0, 1.0) * overlap_samples;
  const auto swap_ramp =
    std::max(1.0, config.bass_swap_seconds * config.sample_rate);

  TransitionResult result;
  result.channels.assign(channel_count, std::vector<float>(overlap_samples, 0.0f));

  for (size_t channel = 0; channel < channel_count; ++channel) {
    std::vector<float> from(overlap_samples);
    std::vector<float> to(overlap_samples);
    for (size_t index = 0; index < overlap_samples; ++index) {
      from[index] = stretched[channel][static_cast<size_t>(outgoing_start) + index];
      to[index] = incoming.channels[channel][static_cast<size_t>(incoming_start) + index];
    }
    // Interpolating between a signal and its own high-passed copy is a clean
    // low shelf, and avoids recomputing biquad coefficients per sample.
    const auto from_high = ApplyHighPass(from, crossover);
    const auto to_high = ApplyHighPass(to, crossover);

    auto& destination = result.channels[channel];
    for (size_t index = 0; index < overlap_samples; ++index) {
      const double progress =
        static_cast<double>(index) / static_cast<double>(overlap_samples);
      // Equal power, so the perceived level stays flat across the overlap
      // rather than dipping in the middle the way a linear fade does.
      const auto fade_out = std::cos(static_cast<float>(progress) * kPi * 0.5f);
      const auto fade_in = std::sin(static_cast<float>(progress) * kPi * 0.5f);

      // Exactly one track owns the low end at any instant; the handover is a
      // short ramp centred on the swap point.
      const auto handover = Smooth((static_cast<double>(index) - swap_point) / swap_ramp + 0.5);
      const auto from_bass = 1.0f - handover;
      const auto to_bass = handover;

      const auto from_mixed = from_high[index] + from_bass * (from[index] - from_high[index]);
      const auto to_mixed = to_high[index] + to_bass * (to[index] - to_high[index]);
      destination[index] = from_mixed * fade_out + to_mixed * fade_in;
    }
  }

  result.rendered = true;
  result.stretch_ratio = ratio;
  result.bpm = incoming.bpm;
  return result;
}

}  // namespace orchard
