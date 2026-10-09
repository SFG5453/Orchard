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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "slop/slop_fingerprint.h"

#include <jni.h>
#include <bit>
#include <cstdint>
#include <vector>

namespace {
SlopFingerprintDetector *detector(jlong handle) {
  return reinterpret_cast<SlopFingerprintDetector *>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_sfg_orchard_mobile_playback_slop_SlopNative_create(
    JNIEnv *env, jobject, jint rate, jint channels, jbyteArray model) {
  if (!model || env->GetArrayLength(model) != 3586 * 4) return 0;
  std::vector<jbyte> bytes(3586 * 4);
  env->GetByteArrayRegion(model, 0, bytes.size(), bytes.data());
  if (env->ExceptionCheck()) return 0;
  std::vector<float> weights(3586);
  for (size_t i = 0; i < weights.size(); ++i) {
    uint32_t bits = 0;
    for (int b = 0; b < 4; ++b)
      bits |= uint32_t(uint8_t(bytes[i * 4 + b])) << (b * 8);
    weights[i] = std::bit_cast<float>(bits);
  }
  return reinterpret_cast<jlong>(SlopFingerprintDetector::create(rate, channels, weights).release());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sfg_orchard_mobile_playback_slop_SlopNative_push(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint count) {
  if (!handle || !samples || count <= 0 || count > env->GetArrayLength(samples) || count % 2) return;
  // Copy before DSP so the VM can move objects while the FFT is running.
  std::vector<float> pcm(count);
  env->GetFloatArrayRegion(samples, 0, count, pcm.data());
  if (!env->ExceptionCheck()) detector(handle)->push(pcm.data(), count / 2);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sfg_orchard_mobile_playback_slop_SlopNative_full(JNIEnv *, jobject, jlong handle) {
  return handle && detector(handle)->isFull();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_sfg_orchard_mobile_playback_slop_SlopNative_verdict(JNIEnv *env, jobject, jlong handle) {
  if (!handle) return nullptr;
  const auto value = detector(handle)->verdict();
  if (!value) return nullptr;
  const float values[] = {value->probability, value->seconds};
  auto result = env->NewFloatArray(2);
  if (result) env->SetFloatArrayRegion(result, 0, 2, values);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sfg_orchard_mobile_playback_slop_SlopNative_free(JNIEnv *, jobject, jlong handle) {
  delete detector(handle);
}
