#include "rubberband_stretch.h"

#include <algorithm>
#include <cmath>

#include "rubberband/RubberBandStretcher.h"

namespace orchard {
namespace {

// Block size study() and process() are fed in, matching Rubber Band's own
// reference CLI. Not a quality setting: feeding the whole input as one giant
// block still produces correct output (the offline mode buffers everything
// regardless), but it skips the sizing setMaxProcessSize() exists to give,
// and R3 responds by repeatedly doubling its internal buffers mid-call and
// logging a warning about it on every single stretch. Chunking avoids that
// for no cost, since this is already reading from and writing to in-memory
// vectors either way.
constexpr size_t kProcessBlockFrames = 1024;
// How many frames are pulled out per retrieve() call; independent of the
// process block size, just a scratch-buffer bound.
constexpr size_t kDrainChunkFrames = 8192;

// Runs the actual stretch. Split out so the public entry point can wrap it in
// one try/catch: Rubber Band's own code does throw on some internal error
// paths (unlike the rest of this codebase, which reports failure through
// return values), and an exception escaping across the libuv worker-thread
// boundary this runs on -- uncaught -- calls std::terminate and takes the
// whole process down rather than just failing the one transition.
std::vector<std::vector<float>> StretchUnchecked(
  const std::vector<std::vector<float>>& channels,
  double sample_rate,
  double ratio
) {
  const auto input_length = channels.front().size();
  const size_t channel_count = channels.size();
  std::vector<const float*> input_pointers(channel_count);
  for (size_t channel = 0; channel < channel_count; ++channel) {
    input_pointers[channel] = channels[channel].data();
  }

  using RubberBand::RubberBandStretcher;
  // OptionChannelsTogether over the OptionChannelsApart default: Apart is
  // documented as costing "mono compatibility (stereo mixes can sound
  // phasy)" in exchange for per-channel fidelity, which is the same failure
  // mode the old WSOLA implementation's shared correlation guide existed
  // specifically to avoid ("searching independently per channel would
  // decorrelate them"). A transition renderer downstream sums a low band
  // across both channels at the bass crossover and eventually plays the
  // result back in stereo, so mono/phase compatibility matters here more
  // than it would for, say, a single-instrument pitch shifter.
  RubberBandStretcher stretcher(
    static_cast<size_t>(std::llround(sample_rate)),
    channel_count,
    RubberBandStretcher::OptionProcessOffline |
      RubberBandStretcher::OptionEngineFiner |
      RubberBandStretcher::OptionChannelsTogether,
    ratio
  );
  stretcher.setExpectedInputDuration(input_length);
  stretcher.setMaxProcessSize(kProcessBlockFrames);

  // Offline mode requires the whole input through study() before any
  // process() call, as a sequence of blocks or a single large block; blocks
  // matching setMaxProcessSize is what the reference implementation itself
  // does, and is what avoids the mid-call input-buffer growth a single giant
  // block causes.
  for (size_t position = 0; position < input_length; position += kProcessBlockFrames) {
    const auto count = std::min(kProcessBlockFrames, input_length - position);
    std::vector<const float*> block(channel_count);
    for (size_t channel = 0; channel < channel_count; ++channel) {
      block[channel] = input_pointers[channel] + position;
    }
    stretcher.study(block.data(), count, position + count >= input_length);
  }

  // Offline mode pads the start internally to prime the analysis window; the
  // corresponding delay must be dropped from the front of the output or every
  // downstream anchor (the transition's overlap offset, in particular) would
  // be off by that many samples relative to the un-stretched track.
  int to_drop = stretcher.getStartDelay();

  std::vector<std::vector<float>> output(channel_count);
  const auto estimate = static_cast<size_t>(std::llround(input_length * ratio)) + kDrainChunkFrames;
  for (auto& channel : output) channel.reserve(estimate);

  std::vector<std::vector<float>> scratch(channel_count, std::vector<float>(kDrainChunkFrames));
  std::vector<float*> scratch_pointers(channel_count);
  for (size_t channel = 0; channel < channel_count; ++channel) {
    scratch_pointers[channel] = scratch[channel].data();
  }

  const auto drain = [&]() {
    // NO_THREADING means process() already computed everything synchronously
    // by the time it returns, so `available() <= 0` here means "nothing more
    // is coming" rather than "still catching up" -- there is no background
    // thread to wait on, so this never needs to poll.
    int available;
    while ((available = stretcher.available()) > 0) {
      const auto want = std::min(static_cast<size_t>(available), kDrainChunkFrames);
      const auto got = stretcher.retrieve(scratch_pointers.data(), want);
      size_t use_from = 0;
      if (to_drop > 0) {
        const auto drop_here = std::min(static_cast<size_t>(to_drop), got);
        to_drop -= static_cast<int>(drop_here);
        use_from = drop_here;
      }
      for (size_t channel = 0; channel < channel_count; ++channel) {
        output[channel].insert(
          output[channel].end(),
          scratch[channel].begin() + static_cast<long>(use_from),
          scratch[channel].begin() + static_cast<long>(got)
        );
      }
    }
  };

  // Draining after every process() block, the same as the reference CLI,
  // keeps R3's internal output queue from growing to hold the entire
  // stretched track before anything is ever retrieved -- the same
  // buffer-growth problem setMaxProcessSize solves on the input side, just on
  // the output side instead.
  for (size_t position = 0; position < input_length; position += kProcessBlockFrames) {
    const auto count = std::min(kProcessBlockFrames, input_length - position);
    std::vector<const float*> block(channel_count);
    for (size_t channel = 0; channel < channel_count; ++channel) {
      block[channel] = input_pointers[channel] + position;
    }
    stretcher.process(block.data(), count, position + count >= input_length);
    drain();
  }
  return output;
}

}  // namespace

std::vector<std::vector<float>> RubberBandTimeStretch(
  const std::vector<std::vector<float>>& channels,
  double sample_rate,
  double ratio
) {
  if (channels.empty() || !std::isfinite(sample_rate) || sample_rate < 1000) return {};
  if (!std::isfinite(ratio) || ratio <= 0) return {};
  const auto input_length = channels.front().size();
  if (input_length == 0) return {};
  for (const auto& channel : channels) {
    if (channel.size() != input_length) return {};
  }

  try {
    return StretchUnchecked(channels, sample_rate, ratio);
  } catch (...) {
    // Matches every other refusal path in this file: the caller treats an
    // empty result as "do not attempt this transition," not as a crash.
    return {};
  }
}

}  // namespace orchard
