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

// JNI bridge to the vocal-separation front end.
//
// Unlike the mel front end this one is stereo and linear-frequency, because
// that is what open-unmix was trained on. The layout it produces is bin-major
// rather than frame-major specifically so it matches the model's tensor shape
// [1, 2, bins, frames] with no transpose on the Kotlin side.

#include <jni.h>

#include <vector>

#include "analyzer/vocal_spectrogram.h"

extern "C" {

// Takes the two channels as separate arrays rather than one interleaved one,
// mirroring the planar layout the front end wants and avoiding a deinterleave
// on either side of the boundary.
//
// The offset and length exist because the model's input width is fixed and
// shorter than the region the caller decodes, so it hands over a window of what
// it already has. Copying that window out on the Java side first would cost
// megabytes of heap for data that is about to be copied into these vectors
// anyway.
JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray left,
    jfloatArray right,
    jint offset,
    jint length,
    jdouble sample_rate) {
  if (offset < 0 || length < 0) return env->NewFloatArray(0);
  if (offset + length > env->GetArrayLength(left) ||
      offset + length > env->GetArrayLength(right)) {
    return env->NewFloatArray(0);
  }

  std::vector<std::vector<float>> channels(2);
  channels[0].resize(static_cast<size_t>(length));
  channels[1].resize(static_cast<size_t>(length));
  if (length > 0) {
    env->GetFloatArrayRegion(left, offset, length, channels[0].data());
    env->GetFloatArrayRegion(right, offset, length, channels[1].data());
  }

  const orchard::VocalSpectrogram spectrogram =
      orchard::ComputeVocalSpectrogram(channels, sample_rate);

  const jsize produced = static_cast<jsize>(spectrogram.values.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) return nullptr;
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, spectrogram.values.data());
  }
  return result;
}

JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeBins(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(orchard::kVocalSpectrogramBins);
}

JNIEXPORT jdouble JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeSampleRate(
    JNIEnv* /* env */, jclass /* clazz */) {
  return orchard::kVocalSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeHop(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(orchard::kVocalSpectrogramHop);
}

JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeFftSize(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(orchard::kVocalSpectrogramFft);
}

}  // extern "C"
