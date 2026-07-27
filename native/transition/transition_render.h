// Offline renderer for a single beat-matched transition between two tracks.
// Produces one finished stereo overlap buffer that the player can schedule as
// a plain audio source, replacing per-node gain and filter automation with a
// deterministic, testable mix.
//
// The outgoing track is the one that gets time-scaled. It is discarded once
// the overlap ends, so any tempo artefact dies with it and the incoming track
// runs at its native rate throughout -- there is no seam, and no tempo step,
// at the point where playback hands over. Stretching the incoming instead
// would leave it detuned from its own file for the rest of playback.
//
// All PCM is planar (non-interleaved) Float32 in [-1, 1], every channel the
// same length. Calls borrow their inputs only until they return, own all
// returned storage, and are reentrant. Rendering allocates and performs O(n)
// work, so it belongs on a worker thread and must never run in a real-time
// audio callback.

#pragma once

#include <string>
#include <vector>

namespace orchard {

struct TransitionSource {
  std::vector<std::vector<float>> channels;
  // Seconds from the start of `channels` to the beat the mix aligns on.
  // Both tracks are positioned so these two instants coincide.
  double anchor = 0;
  double bpm = 0;
};

struct TransitionConfig {
  double sample_rate = 44100;
  // Overlap length in beats of the incoming track's grid, which is the grid
  // the finished transition runs on.
  double beats = 32;
  // Point in the overlap, as a fraction, where the low end hands over from the
  // outgoing to the incoming track. Callers should quantize this to a downbeat,
  // so the effective swap lands on the nearest one rather than exactly here.
  //
  // Later than the midpoint by choice: the equal-power fades cross at 0.5, and
  // handing the low end over at the same instant makes the incoming track
  // arrive early, because it gains the bass while still fading up. Holding the
  // low end on the outgoing track past the crossover was judged better by ear
  // on real material than 0.55.
  double bass_swap = 0.7;
  // Corner frequency of the bass handover. Everything below it belongs to
  // exactly one track at a time, which is what keeps the overlap from turning
  // to mud when two kick drums collide.
  double bass_crossover_hz = 200;
  // Duration of the low-end handover ramp.
  double bass_swap_seconds = 0.75;
  // Fraction of the outgoing audio over which it glides onto the incoming
  // tempo instead of starting there.
  //
  // Defaults to off, and should stay off for an overlap that begins at the
  // first sample: during the glide the outgoing track is by definition not on
  // the incoming grid, and it is loudest exactly then, so the listener hears a
  // beat clash through the opening of the mix. Measured on a 120 -> 126 BPM
  // pair, a quarter-length glide held the opening at 123 BPM instead of 126.
  // It is only useful when the caller passes extra outgoing audio ahead of the
  // overlap, so the tempo move lands while that track is still playing solo.
  double tempo_glide = 0;
};

struct TransitionResult {
  std::vector<std::vector<float>> channels;
  // Time-scaling applied to the outgoing track; 1 when the tempos matched.
  double stretch_ratio = 1;
  // Tempo the rendered overlap runs at, equal to the incoming track's.
  double bpm = 0;
  bool rendered = false;
  // Empty when `rendered`; otherwise why the pairing was refused, so callers
  // can fall back to a plain crossfade and log a reason.
  std::string rejected;
};

/**
 * Renders the overlap between two tracks as a single mixed buffer.
 *
 * Refuses rather than throws when the pairing cannot be rendered
 * transparently: missing or absurd tempo, a stretch beyond
 * kMaxTransparentRatioDeviation, or too little audio on either side to fill
 * the requested overlap. Callers are expected to treat a refusal as "use the
 * ordinary crossfade instead".
 */
TransitionResult RenderTransition(
  const TransitionSource& outgoing,
  const TransitionSource& incoming,
  const TransitionConfig& config
);

}  // namespace orchard
