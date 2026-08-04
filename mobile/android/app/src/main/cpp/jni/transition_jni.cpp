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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// JNI bridge to the beat-matched transition renderer.
//
// The renderer produces one finished stereo overlap buffer rather than
// automating gain on a live graph, which is what makes it deterministic and
// testable -- and what lets it time-stretch the outgoing track, which no amount
// of volume automation can do.
//
// The result crosses as a single float array with a small header rather than as
// an object, because the only failure mode the caller cares about is "could not
// render, use the plain crossfade", which an empty array already says.

#include <jni.h>

#include <android/log.h>

#include <vector>

#include "transition/transition_render.h"

namespace {

std::vector<float> ToVector(JNIEnv* env, jfloatArray array) {
  const jsize count = env->GetArrayLength(array);
  std::vector<float> values(static_cast<size_t>(count));
  if (count > 0) env->GetFloatArrayRegion(array, 0, count, values.data());
  return values;
}

}  // namespace

extern "C" {

/**
 * Renders the overlap and returns it as:
 *
 *   [0]   stretch ratio applied to the outgoing track
 *   [1]   frame count per channel
 *   [2..] left channel, then right channel
 *
 * An empty array means the pairing was refused -- absurd tempo, a stretch
 * beyond what stays transparent, or too little audio to fill the overlap. That
 * is a routing decision, not an error: the caller falls back to the ordinary
 * crossfade, which is what it would have done anyway.
 */
JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_TransitionRenderer_nativeRender(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray outgoing_left,
    jfloatArray outgoing_right,
    jdouble outgoing_anchor,
    jdouble outgoing_bpm,
    jfloatArray incoming_left,
    jfloatArray incoming_right,
    jdouble incoming_anchor,
    jdouble incoming_bpm,
    jdouble sample_rate,
    jdouble beats,
    jdouble handoff,
    jdouble bed,
    jdouble bass_swap,
    jdouble filter_sweep,
    jfloatArray vocal_duck_curve) {
  orchard::TransitionSource outgoing;
  outgoing.channels = {ToVector(env, outgoing_left), ToVector(env, outgoing_right)};
  outgoing.anchor = outgoing_anchor;
  outgoing.bpm = outgoing_bpm;

  orchard::TransitionSource incoming;
  incoming.channels = {ToVector(env, incoming_left), ToVector(env, incoming_right)};
  incoming.anchor = incoming_anchor;
  incoming.bpm = incoming_bpm;

  orchard::TransitionConfig config;
  config.sample_rate = sample_rate;
  config.beats = beats;
  config.handoff = handoff;
  config.bed = bed;
  config.bass_swap = bass_swap;
  config.filter_sweep = filter_sweep;
  if (vocal_duck_curve != nullptr) {
    config.vocal_duck_curve = ToVector(env, vocal_duck_curve);
  }

  const orchard::TransitionResult result =
      orchard::RenderTransition(outgoing, incoming, config);

  if (!result.rendered || result.channels.size() < 2 || result.channels[0].empty()) {
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "OrchardTransition",
        "Refused: %s",
        result.rejected.empty() ? "unknown" : result.rejected.c_str());
    return env->NewFloatArray(0);
  }

  const size_t frames = result.channels[0].size();
  const jsize total = static_cast<jsize>(2 + frames * 2);
  jfloatArray out = env->NewFloatArray(total);
  if (out == nullptr) return nullptr;

  const float header[2] = {
      static_cast<float>(result.stretch_ratio),
      static_cast<float>(frames),
  };
  env->SetFloatArrayRegion(out, 0, 2, header);
  env->SetFloatArrayRegion(out, 2, static_cast<jsize>(frames), result.channels[0].data());
  env->SetFloatArrayRegion(
      out, static_cast<jsize>(2 + frames), static_cast<jsize>(frames), result.channels[1].data());
  return out;
}

}  // extern "C"
