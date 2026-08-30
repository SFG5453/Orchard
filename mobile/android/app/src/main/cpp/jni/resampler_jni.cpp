/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

// JNI boundary for Android's streaming windowed-sinc resampler. Analyzer DSP
// and model frontends live in Earmark and are exported by orchard_earmark.

#include <jni.h>

#include <vector>

#include "analyzer/resampler.h"

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResample(
    JNIEnv* env, jclass, jfloatArray samples, jdouble input_rate,
    jdouble output_rate) {
  const jsize count = env->GetArrayLength(samples);
  std::vector<float> input(static_cast<size_t>(count));
  if (count > 0) env->GetFloatArrayRegion(samples, 0, count, input.data());
  const auto resampled = orchard::Resample(input, input_rate, output_rate);
  auto result = env->NewFloatArray(static_cast<jsize>(resampled.size()));
  if (result != nullptr && !resampled.empty()) {
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(resampled.size()), resampled.data());
  }
  return result;
}

JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResamplePeriod(
    JNIEnv*, jclass, jdouble input_rate, jdouble output_rate) {
  return static_cast<jint>(orchard::ResamplePeriod(input_rate, output_rate));
}

JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResampleContext(
    JNIEnv*, jclass, jdouble input_rate, jdouble output_rate) {
  return static_cast<jint>(orchard::ResampleContext(input_rate, output_rate));
}

JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_MelSpectrogram_nativeResampleInterior(
    JNIEnv* env, jclass, jfloatArray samples, jint offset, jint length,
    jdouble input_rate, jdouble output_rate, jint leading_context,
    jint trailing_context) {
  if (offset < 0 || length < 0 || leading_context < 0 || trailing_context < 0 ||
      offset + length > env->GetArrayLength(samples)) {
    return env->NewFloatArray(0);
  }
  std::vector<float> window(static_cast<size_t>(length));
  if (length > 0) env->GetFloatArrayRegion(samples, offset, length, window.data());
  const auto resampled = orchard::ResampleInterior(
      window, input_rate, output_rate, static_cast<size_t>(leading_context),
      static_cast<size_t>(trailing_context));
  auto result = env->NewFloatArray(static_cast<jsize>(resampled.size()));
  if (result != nullptr && !resampled.empty()) {
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(resampled.size()), resampled.data());
  }
  return result;
}

}  // extern "C"
