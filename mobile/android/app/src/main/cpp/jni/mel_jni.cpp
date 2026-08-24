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

// JNI bridge to the mel front end.
//
// Kept as thin as a bridge can be: it copies the sample array in, calls
// ComputeBeatSpectrogram, and hands back a flat float[]. No policy, no
// buffering, no threading -- all of that belongs on the Kotlin side where it can
// be cancelled and tested.

#include <jni.h>

#include <vector>

#include "analyzer/mel_spectrogram.h"
#include "analyzer/resampler.h"

extern "C" {

// Converts mono float PCM to the model's rate. Separate from nativeCompute
// because the caller decodes at whatever rate the container carries and only
// then knows what conversion is needed.
JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResample(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble input_rate,
    jdouble output_rate) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(samples, 0, count, input.data());
  }

  const std::vector<float> resampled =
      orchard::Resample(input, input_rate, output_rate);

  const jsize produced = static_cast<jsize>(resampled.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, resampled.data());
  }
  return result;
}

// The three below let a caller resample a long stream a block at a time. The
// arithmetic that decides where one block's output starts belongs next to the
// filter that defines it, so Kotlin only has to stage the samples.
JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResamplePeriod(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jdouble input_rate,
    jdouble output_rate) {
  return static_cast<jint>(orchard::ResamplePeriod(input_rate, output_rate));
}

JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResampleContext(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jdouble input_rate,
    jdouble output_rate) {
  return static_cast<jint>(orchard::ResampleContext(input_rate, output_rate));
}

// Takes an offset and a length rather than a trimmed array because the caller
// holds one staging buffer and slides a window along it; copying the window out
// first would allocate as much again on the Java heap, which is the whole thing
// this API exists to avoid.
JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResampleInterior(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jint offset,
    jint length,
    jdouble input_rate,
    jdouble output_rate,
    jint leading_context,
    jint trailing_context) {
  if (offset < 0 || length < 0 || leading_context < 0 || trailing_context < 0) {
    return env->NewFloatArray(0);
  }
  if (offset + length > env->GetArrayLength(samples)) {
    return env->NewFloatArray(0);
  }

  std::vector<float> window(static_cast<size_t>(length));
  if (length > 0) {
    env->GetFloatArrayRegion(samples, offset, length, window.data());
  }

  const std::vector<float> resampled = orchard::ResampleInterior(
      window, input_rate, output_rate, static_cast<size_t>(leading_context),
      static_cast<size_t>(trailing_context));

  const jsize produced = static_cast<jsize>(resampled.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, resampled.data());
  }
  return result;
}

// Returns the flattened [frames][kBeatSpectrogramMels] spectrogram, or an empty
// array when the front end declined the input (wrong rate, or shorter than one
// padded frame). The caller derives the frame count by dividing, so no second
// return value is needed.
JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble sample_rate) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) {
    env->GetFloatArrayRegion(samples, 0, count, input.data());
  }

  const orchard::BeatSpectrogram spectrogram =
      orchard::ComputeBeatSpectrogram(input, sample_rate);

  const jsize produced = static_cast<jsize>(spectrogram.values.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;  // OOM; the exception is already pending.
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, spectrogram.values.data());
  }
  return result;
}

// The mel band count is part of the model contract rather than a choice, so it
// is read from the header instead of being duplicated in Kotlin.
JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeMelCount(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return static_cast<jint>(orchard::kBeatSpectrogramMels);
}

JNIEXPORT jdouble JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeSampleRate(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return orchard::kBeatSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeHop(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return static_cast<jint>(orchard::kBeatSpectrogramHop);
}

}  // extern "C"
