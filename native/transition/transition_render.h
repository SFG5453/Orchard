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
  // Point in the overlap, as a fraction, where the two tracks cross at equal
  // power. Everything before it is the incoming track's pre-roll: its intro
  // playing underneath the outgoing track, rising like a fader ride rather
  // than a crossfade.
  //
  // A DJ does not start a blend at the incoming track's drop, because that is
  // where its vocal and full arrangement arrive, and the outgoing track is
  // still singing. They bring the intro in early, underneath, and let the drop
  // be the moment the mix changes hands. Callers place this at the incoming
  // drop so the mix changes hands there.
  //
  // 0.5 reproduces the plain symmetric equal-power crossfade.
  double handoff = 0.5;
  // How far along the equal-power curve the pre-roll travels: the incoming
  // track's level at the handoff, and the outgoing track's loss up to it.
  //
  // The pre-roll is a fader ride under a track that is still playing, not the
  // first half of a crossfade. At 0.25 the incoming intro reaches -8 dB while
  // the outgoing has given up 0.7 dB -- a bed -- and the whole audible fade
  // then happens in the tail. Spending the curve's first half on the pre-roll
  // instead (bed = 0.5) costs the outgoing track only 3 dB over what may be
  // fifteen seconds, which reads as no fade at all, and then leaves it fading
  // for the entire tail on top: slow at both ends.
  //
  // 0.5 makes the fade one continuous equal-power curve across the overlap,
  // which with handoff = 0.5 is the plain symmetric crossfade.
  double bed = 0.5;
  // Corner frequency of the bass handover. Everything below it belongs to
  // exactly one track at a time, which is what keeps the overlap from turning
  // to mud when two kick drums collide.
  double bass_crossover_hz = 200;
  double bass_swap_seconds = 0.75;
  // How far the outgoing track's low-pass sweep travels by the end of the
  // overlap, as a fraction of the whole distance from filter_sweep_start_hz
  // down to bass_crossover_hz. 0 leaves the outgoing track on the plain fade;
  // 1 closes it all the way down onto its own bass band.
  //
  // This is the filter ride a DJ does on the outgoing channel, and it is what
  // makes a blend read as a mix rather than two records playing at once. The
  // equal-power fade alone holds both full arrangements at -3 dB each through
  // the middle of the overlap, and two beat-aligned mixes are correlated, so
  // they sum hot exactly where their spectra collide. A moving corner takes
  // the outgoing track's top away first and its mids last, so it thins out
  // and recedes instead of merely getting quieter -- and it is a *sweep*, not
  // a fixed band cut: the ear follows the movement, which is what covers the
  // seam.
  //
  // The sweep only ever touches the band above bass_crossover_hz. The low end
  // is already exclusive to one track at a time by way of bass_swap, so the
  // outgoing kick keeps its weight until it hands over, exactly as before.
  double filter_sweep = 0;
  // Where the sweep starts. Above the top of hearing on purpose: the first
  // part of the ride has to be inaudible, or the transition announces itself.
  double filter_sweep_start_hz = 18000;
  // Optional per-instant vocal-presence multiplier on `filter_sweep`, one
  // value in [0, 1] per equally-spaced control point spanning the overlap
  // exactly (first point at the overlap's start, last at its end) -- the
  // caller has already cropped and aligned it, native code only interpolates.
  //
  // `filter_sweep` alone follows the fade curve, not the music: it costs the
  // outgoing track the same spectrum whether it is singing full-throated or
  // playing an instrumental outro. Where this curve has data, the depth at
  // each instant is `filter_sweep * vocal_duck_curve(t)`, so a track is only
  // filtered out of the way when, and as much as, it is actually carrying a
  // vocal to collide with the incoming one. Empty (the default) reads as a
  // constant 1 and leaves the sweep at its flat depth -- this is a refinement
  // of the sweep, not a dependency of it.
  //
  // Applied as a running maximum, never the instantaneous value: a filter
  // that reopens mid-blend is a sound no DJ makes, and the wobble is far more
  // audible than the extra attenuation it would save.
  std::vector<float> vocal_duck_curve;
};

struct TransitionResult {
  std::vector<std::vector<float>> channels;
  double stretch_ratio = 1;
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
