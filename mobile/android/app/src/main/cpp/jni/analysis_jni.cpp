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

// JNI bridge to the whole-track analyzer.
//
// AnalysisResult carries about twenty-five fields including strings, which is
// more than is worth marshalling field by field through JNI. It is serialized
// to JSON instead: analysis runs once per track, so the cost is irrelevant
// beside the decode and inference around it, and a string is far easier to log
// and to test against than a hand-packed buffer.
//
// Only the subset the transition policy actually reads is emitted. Chroma, the
// mid and high energy curves, loudness, peak and dynamic range are computed by
// the analyzer but nothing downstream consumes them, and emitting them would
// mean three more float arrays per track for no reader.

#include <jni.h>

#include <string>
#include <vector>

#include "analyzer/audio_analysis.h"

namespace {

// The analyzer's strings are its own literals -- key names like "C# minor" and
// candidate types like "main_drop" -- so they are known ASCII with nothing to
// escape. Anything unexpected is dropped rather than emitted unescaped.
void AppendString(std::string& out, const std::string& value) {
  out += '"';
  for (const char character : value) {
    if (character >= 32 && character < 127 && character != '"' && character != '\\') {
      out += character;
    }
  }
  out += '"';
}

void AppendNumber(std::string& out, double value) {
  // Not finite means the field never got a defensible value; null reads as
  // absent on the Kotlin side, which is what every consumer already handles.
  if (!(value == value) || value > 1e308 || value < -1e308) {
    out += "null";
    return;
  }
  char buffer[32];
  snprintf(buffer, sizeof(buffer), "%.6g", value);
  out += buffer;
}

void AppendDoubles(std::string& out, const std::vector<double>& values) {
  out += '[';
  for (size_t index = 0; index < values.size(); ++index) {
    if (index > 0) out += ',';
    AppendNumber(out, values[index]);
  }
  out += ']';
}

void AppendEnergyCurve(std::string& out, const std::vector<orchard::EnergyPoint>& points) {
  out += '[';
  for (size_t index = 0; index < points.size(); ++index) {
    if (index > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[index].time);
    out += ",\"e\":";
    AppendNumber(out, points[index].energy);
    out += '}';
  }
  out += ']';
}

void AppendCuePoints(std::string& out, const std::vector<orchard::MixCuePoint>& points) {
  out += '[';
  for (size_t index = 0; index < points.size(); ++index) {
    if (index > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[index].time);
    out += ",\"s\":";
    AppendNumber(out, points[index].score);
    out += ",\"y\":";
    AppendString(out, points[index].type);
    out += '}';
  }
  out += ']';
}

void AppendField(std::string& out, const char* name, double value, bool first = false) {
  if (!first) out += ',';
  out += '"';
  out += name;
  out += "\":";
  AppendNumber(out, value);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_TrackFeatures_nativeAnalyze(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble sample_rate,
    jdouble duration) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(samples, 0, count, input.data());
  }

  const orchard::AnalysisResult result =
      orchard::AnalyzeAudio(input, sample_rate, duration);

  std::string json;
  // A whole-track energy curve dominates the output; reserving up front keeps
  // this from repeatedly reallocating a string that reaches tens of kilobytes.
  json.reserve(8192 + result.energy_curve.size() * 24);

  json += '{';
  AppendField(json, "duration", result.duration, true);
  AppendField(json, "bpm", result.bpm);
  AppendField(json, "beatInterval", result.beat_interval);
  AppendField(json, "firstBeat", result.first_beat);
  AppendField(json, "beatConfidence", result.beat_confidence);
  AppendField(json, "keyConfidence", result.key_confidence);
  AppendField(json, "audibleStartTime", result.audible_start_time);
  AppendField(json, "pickupTime", result.pickup_time);
  AppendField(json, "introEndTime", result.intro_end_time);
  AppendField(json, "outroStartTime", result.outro_start_time);
  AppendField(json, "contentEndTime", result.content_end_time);
  AppendField(json, "mixInTime", result.mix_in_time);
  AppendField(json, "mixOutTime", result.mix_out_time);
  AppendField(json, "vocalProbability", result.vocal_probability);

  json += ",\"key\":";
  AppendString(json, result.key);
  json += ",\"downbeats\":";
  AppendDoubles(json, result.downbeats);
  json += ",\"phraseBoundaries\":";
  AppendDoubles(json, result.phrase_boundaries);
  json += ",\"vocalActivityMask\":";
  AppendDoubles(json, result.vocal_activity_mask);
  json += ",\"energyCurve\":";
  AppendEnergyCurve(json, result.energy_curve);
  json += ",\"lowEnergyCurve\":";
  AppendEnergyCurve(json, result.low_energy_curve);
  json += ",\"mixInCandidates\":";
  AppendCuePoints(json, result.mix_in_candidates);
  json += ",\"mixOutCandidates\":";
  AppendCuePoints(json, result.mix_out_candidates);
  json += '}';

  return env->NewStringUTF(json.c_str());
}

JNIEXPORT jdouble JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_TrackFeatures_nativeSampleRate(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  // The rate the analyzer's window and hop constants assume.
  return 11025.0;
}

}  // extern "C"
