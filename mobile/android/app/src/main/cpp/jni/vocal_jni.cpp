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

#include <algorithm>
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

// Writes the spectrogram straight into a direct buffer the caller owns and
// returns the frames written.
//
// That buffer is the one the model reads, so the window never exists as a Java
// array: a [1, 2, bins, 960] tensor is 15 MB, and going through a float[] meant
// it existed twice over, because the runtime copies a non-direct input buffer
// into a direct one of its own before inference. Two analyses run at once by
// design, and that arithmetic is what took the process down.
//
// `frame_stride` is the model's fixed width. Writing each bin's frames that far
// apart into a cleared buffer is also what zero-pads a short window, so no
// separate padding pass is needed.
JNIEXPORT jint JNICALL
Java_dev_sfg_orchard_mobile_playback_smart_VocalSpectrogram_nativeComputeInto(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray left,
    jfloatArray right,
    jint offset,
    jint length,
    jdouble sample_rate,
    jobject destination,
    jint frame_stride) {
  if (offset < 0 || length <= 0 || frame_stride <= 0) return 0;
  if (offset + length > env->GetArrayLength(left) ||
      offset + length > env->GetArrayLength(right)) {
    return 0;
  }

  auto* out = static_cast<float*>(env->GetDirectBufferAddress(destination));
  const jlong capacity = env->GetDirectBufferCapacity(destination);
  if (out == nullptr || capacity < 0) return 0;

  const size_t stride = static_cast<size_t>(frame_stride);
  const size_t required =
      orchard::kVocalSpectrogramChannels * orchard::kVocalSpectrogramBins * stride;
  if (static_cast<size_t>(capacity) / sizeof(float) < required) return 0;

  std::vector<std::vector<float>> channels(orchard::kVocalSpectrogramChannels);
  channels[0].resize(static_cast<size_t>(length));
  channels[1].resize(static_cast<size_t>(length));
  env->GetFloatArrayRegion(left, offset, length, channels[0].data());
  env->GetFloatArrayRegion(right, offset, length, channels[1].data());

  const orchard::VocalSpectrogram spectrogram =
      orchard::ComputeVocalSpectrogram(channels, sample_rate);
  if (spectrogram.frames == 0 || spectrogram.frames > stride) return 0;

  std::fill_n(out, required, 0.0f);
  for (size_t channel = 0; channel < orchard::kVocalSpectrogramChannels; ++channel) {
    for (size_t bin = 0; bin < orchard::kVocalSpectrogramBins; ++bin) {
      const size_t row = channel * orchard::kVocalSpectrogramBins + bin;
      std::copy_n(spectrogram.values.data() + row * spectrogram.frames,
                  spectrogram.frames,
                  out + row * stride);
    }
  }
  return static_cast<jint>(spectrogram.frames);
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
