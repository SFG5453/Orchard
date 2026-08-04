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

#include "transition_render.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "rubberband_stretch.h"

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

// RBJ cookbook second-order Butterworth low-pass, normalized by a0.
Biquad LowPass(double cutoff, double sample_rate) {
  const double w0 = 2.0 * kPi * cutoff / sample_rate;
  const double cosine = std::cos(w0);
  const double alpha = std::sin(w0) / (2.0 * 0.70710678);
  const double a0 = 1.0 + alpha;
  Biquad filter;
  filter.b0 = static_cast<float>(((1.0 - cosine) / 2.0) / a0);
  filter.b1 = static_cast<float>((1.0 - cosine) / a0);
  filter.b2 = filter.b0;
  filter.a1 = static_cast<float>((-2.0 * cosine) / a0);
  filter.a2 = static_cast<float>((1.0 - alpha) / a0);
  return filter;
}

// Runs `filter` over the signal twice. Two identical Butterworth sections in
// series is a fourth-order Linkwitz-Riley response at 24 dB/octave -- the
// slope DJ mixers use for a bass kill -- so this builds either half of an LR4
// crossover depending on which section it is handed. A single 12 dB/octave
// section leaves the incoming low end only about 15 dB down at the crossover,
// which is audible as bass arriving before the swap.
//
// Direct form I, run forward only. The resulting phase shift is identical on
// every channel and on both tracks, so it does not smear the stereo image or
// misalign the two sides of the mix against each other.
std::vector<float> ApplyLinkwitzRiley(const std::vector<float>& input, const Biquad& filter) {
  std::vector<float> output(input);
  for (int section = 0; section < 2; ++section) {
    float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    for (size_t index = 0; index < output.size(); ++index) {
      const float x0 = output[index];
      const float y0 =
        filter.b0 * x0 + filter.b1 * x1 + filter.b2 * x2 - filter.a1 * y1 - filter.a2 * y2;
      output[index] = y0;
      x2 = x1;
      x1 = x0;
      y2 = y1;
      y1 = y0;
    }
  }
  return output;
}

// A fourth-order Linkwitz-Riley low-pass whose corner may move while it runs.
//
// ApplyLinkwitzRiley cannot do this: it runs section one over the whole buffer
// and section two afterwards, which is only equivalent for a filter whose
// coefficients never change. A moving corner has to run both sections in
// lockstep, one sample at a time, so this keeps its own state and takes new
// coefficients between blocks. The state is deliberately *not* reset when the
// coefficients change -- that is what makes the corner glide rather than step.
class SweepingLowPass {
 public:
  void SetCutoff(double cutoff, double sample_rate) {
    filter_ = LowPass(cutoff, sample_rate);
  }

  float Process(float sample) {
    for (auto& section : sections_) {
      const float x0 = sample;
      const float y0 = filter_.b0 * x0 + filter_.b1 * section.x1 + filter_.b2 * section.x2 -
                       filter_.a1 * section.y1 - filter_.a2 * section.y2;
      section.x2 = section.x1;
      section.x1 = x0;
      section.y2 = section.y1;
      section.y1 = y0;
      sample = y0;
    }
    return sample;
  }

 private:
  struct State {
    float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  };
  Biquad filter_;
  State sections_[2];
};

// Samples between coefficient updates. 64 is 1.5 ms at 44.1 kHz, far shorter
// than anything the sweep does, so the corner moves by a fraction of a percent
// per update and the steps are inaudible; recomputing per sample would cost
// two transcendentals a sample for no audible gain.
constexpr size_t kSweepBlock = 64;

// How steep the sweep's logistic is through the middle of the ride. At namespace
// scope rather than inside SweepShape because a lambda there would have to
// capture it: MSVC requires an explicit capture for a local constexpr, where GCC
// and Clang treat the use as a constant expression and let it through.
constexpr double kSweepSteepness = 6.0;

double SweepLogistic(double x) {
  return 1.0 / (1.0 + std::exp(-kSweepSteepness * (x - 0.5)));
}

// Time shape of the sweep: flat at both ends, steep through the middle, so the
// ride starts and finishes without a lurch. Normalized to hit exactly 0 and 1
// at the edges -- the raw logistic is 0.047 at t = 0, which would drop the
// corner to 16 kHz before the transition has begun.
float SweepShape(double progress) {
  static const double low = SweepLogistic(0.0);
  static const double span = SweepLogistic(1.0) - low;
  return static_cast<float>((SweepLogistic(std::clamp(progress, 0.0, 1.0)) - low) / span);
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

float Smooth(double progress) {
  const auto clamped = static_cast<float>(std::clamp(progress, 0.0, 1.0));
  return clamped * clamped * (3.0f - 2.0f * clamped);
}

// Maps position in the overlap onto position along the equal-power curve. The
// pre-roll covers the curve up to `bed` and the tail covers the rest, so both
// the length of the pre-roll and how much of the fade it is allowed to spend
// are set independently. Linear within each segment.
double FadePosition(double progress, double handoff, double bed) {
  if (progress <= handoff) return bed * (progress / handoff);
  return bed + (1.0 - bed) * ((progress - handoff) / (1.0 - handoff));
}

// Linearly interpolates `curve` at fractional `progress` through the overlap.
// An empty curve is "no vocal-presence information available" and reads as a
// constant 1 everywhere, so the caller's duck falls back to exactly its old
// flat-depth behaviour rather than silently ducking nothing.
float SampleCurve(const std::vector<float>& curve, double progress) {
  if (curve.empty()) return 1.0f;
  if (curve.size() == 1) return curve.front();
  const auto scaled = std::clamp(progress, 0.0, 1.0) * static_cast<double>(curve.size() - 1);
  const auto lower = static_cast<size_t>(scaled);
  const auto upper = std::min(curve.size() - 1, lower + 1);
  const auto fraction = static_cast<float>(scaled - static_cast<double>(lower));
  return curve[lower] + (curve[upper] - curve[lower]) * fraction;
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
  // the one that moves. `ratio` is output-over-input length, so reaching a
  // faster grid means compressing: a 120 BPM outgoing onto a 126 BPM grid is
  // 120/126, below 1. Inverting this silently detunes the mix by twice the
  // tempo gap in the wrong direction.
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

  const auto stretched = RubberBandTimeStretch(outgoing.channels, config.sample_rate, ratio);
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
  // The crossover is a matched LR4 low/high pair rather than a filter and a
  // subtracted remainder, and it has to be. This matters more, not less, now
  // that the high branch feeds a sweep: the sweep's phase is *designed* to
  // move.
  //
  // A subtractive split is only complementary against the *exact* signal it
  // was subtracted from: `low = x - high` reconstructs x, but `low` is not
  // band-limited -- at 1 kHz a 200 Hz subtractive low still carries 0.56 of
  // the input, because the LR4 high-pass has rotated 33 degrees there and the
  // difference of two nearly-equal-magnitude vectors 33 degrees apart is not
  // small. That is invisible until something downstream alters the phase of
  // the high branch, at which point the two stop cancelling: measured, when
  // the outgoing mids were shaped by a second subtractive split, its allpass
  // rotation (-41 degrees at 1 kHz) turned that residue into a 34% *boost* of
  // the mids, exactly the band that was supposed to be getting out of the way.
  //
  // An LR4 pair sums magnitude-flat by construction and each half is genuinely
  // band-limited (the same 200 Hz low-pass is 0.0016 at 1 kHz), so the bands
  // cannot interfere no matter what later stages do to either one.
  const auto bass_hz = std::clamp(config.bass_crossover_hz, 40.0, 500.0);
  const auto crossover = HighPass(bass_hz, config.sample_rate);
  const auto crossover_low = LowPass(bass_hz, config.sample_rate);
  const auto sweep_depth = std::clamp(config.filter_sweep, 0.0, 1.0);
  // Kept below Nyquist with room to spare: the bilinear transform warps badly
  // as the corner approaches it, and an 18 kHz corner at 22.05 kHz Nyquist is
  // already no longer the response the coefficients claim.
  const auto sweep_start_hz =
    std::clamp(config.filter_sweep_start_hz, bass_hz, config.sample_rate * 0.45);
  // Kept clear of both edges: at exactly 0 or 1 the fade would be a step.
  const auto handoff = std::clamp(config.handoff, 0.05, 0.95);
  // Above 0.5 the pre-roll would fade the outgoing track further than the
  // tail does, which is no longer a pre-roll.
  const auto bed = std::clamp(config.bed, 0.0, 0.5);
  const auto swap_point = std::clamp(config.bass_swap, 0.0, 1.0) * overlap_samples;
  const auto swap_ramp =
    std::max(1.0, config.bass_swap_seconds * config.sample_rate);

  // The corner is the same on every channel and its vocal term has to be
  // accumulated in order, so it is laid out once here rather than recomputed
  // inside the per-channel loop.
  std::vector<float> sweep_cutoff;
  if (sweep_depth > 0) {
    sweep_cutoff.resize((overlap_samples + kSweepBlock - 1) / kSweepBlock);
    const auto log_start = std::log(sweep_start_hz);
    const auto log_end = std::log(bass_hz);
    float presence = 0;
    for (size_t block = 0; block < sweep_cutoff.size(); ++block) {
      const double progress =
        static_cast<double>(block * kSweepBlock) / static_cast<double>(overlap_samples);
      presence = std::max(
        presence, std::clamp(SampleCurve(config.vocal_duck_curve, progress), 0.0f, 1.0f)
      );
      const auto amount = SweepShape(progress) * static_cast<float>(sweep_depth) * presence;
      sweep_cutoff[block] =
        static_cast<float>(std::exp(log_start + (log_end - log_start) * amount));
    }
  }

  TransitionResult result;
  result.channels.assign(channel_count, std::vector<float>(overlap_samples, 0.0f));

  for (size_t channel = 0; channel < channel_count; ++channel) {
    std::vector<float> from(overlap_samples);
    std::vector<float> to(overlap_samples);
    for (size_t index = 0; index < overlap_samples; ++index) {
      from[index] = stretched[channel][static_cast<size_t>(outgoing_start) + index];
      to[index] = incoming.channels[channel][static_cast<size_t>(incoming_start) + index];
    }
    const auto from_high = ApplyLinkwitzRiley(from, crossover);
    const auto to_high = ApplyLinkwitzRiley(to, crossover);
    const auto from_low_band = ApplyLinkwitzRiley(from, crossover_low);
    const auto to_low_band = ApplyLinkwitzRiley(to, crossover_low);
    // Only the outgoing side is filtered: the sweep shapes what leaves, not
    // what arrives. It runs on `from_high`, after the bass split, so closing
    // the corner all the way down can never eat into the low end the swap is
    // separately handing over.
    SweepingLowPass sweep;

    auto& destination = result.channels[channel];
    for (size_t index = 0; index < overlap_samples; ++index) {
      const double progress =
        static_cast<double>(index) / static_cast<double>(overlap_samples);
      const auto position = static_cast<float>(FadePosition(progress, handoff, bed));
      const auto fade_out = std::cos(position * kPi * 0.5f);
      const auto fade_in = std::sin(position * kPi * 0.5f);

      // Exactly one track owns the low end at any instant; the handover is a
      // short ramp centred on the swap point. Equal power again, for the same
      // reason as the main fade: the two low ends are uncorrelated, so linear
      // gains would leave them summing 3 dB down at the midpoint and put an
      // audible hole in the bass exactly where it changes hands.
      const auto handover = Smooth((static_cast<double>(index) - swap_point) / swap_ramp + 0.5);
      const auto from_bass = std::cos(handover * kPi * 0.5f);
      const auto to_bass = std::sin(handover * kPi * 0.5f);

      const auto from_low = from_low_band[index];
      const auto to_low = to_low_band[index];
      // The corner rides down as the fade runs, so the outgoing track loses
      // its top first and its mids last: it recedes rather than just getting
      // quieter, and the movement itself is what covers the seam.
      auto from_upper = from_high[index];
      if (sweep_depth > 0) {
        if (index % kSweepBlock == 0) {
          sweep.SetCutoff(sweep_cutoff[index / kSweepBlock], config.sample_rate);
        }
        from_upper = sweep.Process(from_upper);
      }
      from_upper *= fade_out;
      destination[index] =
        from_upper + to_high[index] * fade_in +
        from_low * from_bass + to_low * to_bass;
    }
  }

  result.rendered = true;
  result.stretch_ratio = ratio;
  result.bpm = incoming.bpm;
  return result;
}

}  // namespace orchard
