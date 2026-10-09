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

#pragma once

// JNI helpers shared by the Connect and Listening Party bindings in this library.

#include <jni.h>

#include <string>

namespace orchard::jni {

inline JavaVM *g_vm = nullptr;

// The Connect loop thread attaches once and detaches as it exits; the JVM aborts
// on a thread that dies attached.
struct ThreadAttachment {
  JNIEnv *env = nullptr;
  bool attached = false;
  ~ThreadAttachment() {
    if (attached && g_vm)
      g_vm->DetachCurrentThread();
  }
};

inline JNIEnv *currentEnv() {
  thread_local ThreadAttachment attachment;
  if (attachment.env)
    return attachment.env;
  if (g_vm->GetEnv(reinterpret_cast<void **>(&attachment.env), JNI_VERSION_1_6) == JNI_OK)
    return attachment.env;
  JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char *>("OrchardConnect"), nullptr};
#ifdef __ANDROID__
  if (g_vm->AttachCurrentThreadAsDaemon(&attachment.env, &args) != JNI_OK)
#else
  if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&attachment.env), &args) != JNI_OK)
#endif
    return nullptr;
  attachment.attached = true;
  return attachment.env;
}

inline std::string text(JNIEnv *env, jstring value) {
  if (!value)
    return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string out = chars ? chars : "";
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

// JSON from the core is UTF-8; NewStringUTF wants modified UTF-8, which differs
// for supplementary characters, so go through a byte array instead.
inline jstring javaString(JNIEnv *env, const std::string &value) {
  jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
  env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
  jclass stringClass = env->FindClass("java/lang/String");
  jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
  jstring charset = env->NewStringUTF("UTF-8");
  auto *result = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
  env->DeleteLocalRef(charset);
  env->DeleteLocalRef(stringClass);
  env->DeleteLocalRef(bytes);
  return result;
}

} // namespace orchard::jni
