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

// JNI for dev.sfg.orchard.mobile.connect.ConnectNative. Strings in, strings out:
// the protocol itself is the shared core, identical to desktop.

#include "connect/node.h"
#include "jni_util.h"

#include <jni.h>

#include <memory>
#include <string>
#include <utility>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

using orchard::jni::currentEnv;
using orchard::jni::g_vm;
using orchard::jni::javaString;
using orchard::jni::text;

class JniHost final : public orchard::connect::NodeHost {
public:
  JniHost(JNIEnv *env, jobject listener) : m_listener(env->NewGlobalRef(listener)) {
    jclass type = env->GetObjectClass(listener);
    m_hubSend = env->GetMethodID(type, "onHubSend", "(Ljava/lang/String;)V");
    m_event = env->GetMethodID(type, "onEvent", "(Ljava/lang/String;)V");
    m_data = env->GetMethodID(type, "onData", "(Ljava/lang/String;Ljava/lang/String;[B)V");
    env->DeleteLocalRef(type);
  }

  void release(JNIEnv *env) {
    if (m_listener)
      env->DeleteGlobalRef(m_listener);
    m_listener = nullptr;
  }

  void hubSend(const std::string &message) override { call(m_hubSend, message); }
  void event(const std::string &json) override { call(m_event, json); }

  void data(const std::string &sessionId, const std::string &headerJson, std::string payload) override {
    JNIEnv *env = currentEnv();
    if (!env || !m_listener)
      return;
    jstring sid = javaString(env, sessionId);
    jstring header = javaString(env, headerJson);
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(payload.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(payload.size()),
                            reinterpret_cast<const jbyte *>(payload.data()));
    env->CallVoidMethod(m_listener, m_data, sid, header, bytes);
    clear(env);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(header);
    env->DeleteLocalRef(sid);
  }

  void log(const std::string &message) override {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "OrchardConnect", "%s", message.c_str());
#else
    (void)message;
#endif
  }

private:
  void call(jmethodID method, const std::string &value) {
    JNIEnv *env = currentEnv();
    if (!env || !m_listener)
      return;
    jstring arg = javaString(env, value);
    env->CallVoidMethod(m_listener, method, arg);
    clear(env);
    env->DeleteLocalRef(arg);
  }

  // A Kotlin exception must not unwind into the Connect loop.
  static void clear(JNIEnv *env) {
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
  }

  jobject m_listener = nullptr;
  jmethodID m_hubSend = nullptr;
  jmethodID m_event = nullptr;
  jmethodID m_data = nullptr;
};

struct Handle {
  std::unique_ptr<JniHost> host;
  std::unique_ptr<orchard::connect::Node> node;
};

Handle *handle(jlong value) { return reinterpret_cast<Handle *>(value); }

} // namespace

#define CONNECT_JNI(name) Java_dev_sfg_orchard_mobile_connect_ConnectNative_##name

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL CONNECT_JNI(create)(JNIEnv *env, jclass, jobject listener) {
  auto *created = new Handle;
  created->host = std::make_unique<JniHost>(env, listener);
  created->node = std::make_unique<orchard::connect::Node>(*created->host);
  return reinterpret_cast<jlong>(created);
}

JNIEXPORT void JNICALL CONNECT_JNI(destroy)(JNIEnv *env, jclass, jlong value) {
  Handle *target = handle(value);
  if (!target)
    return;
  // Joins the Connect thread, so no callback can reach the listener afterwards.
  target->node.reset();
  target->host->release(env);
  delete target;
}

JNIEXPORT void JNICALL CONNECT_JNI(hubOpened)(JNIEnv *, jclass, jlong value) { handle(value)->node->hubOpened(); }

JNIEXPORT void JNICALL CONNECT_JNI(hubMessage)(JNIEnv *env, jclass, jlong value, jstring message) {
  handle(value)->node->hubMessage(text(env, message));
}

JNIEXPORT void JNICALL CONNECT_JNI(hubClosed)(JNIEnv *, jclass, jlong value) { handle(value)->node->hubClosed(); }

JNIEXPORT void JNICALL CONNECT_JNI(setDevice)(JNIEnv *env, jclass, jlong value, jstring json) {
  handle(value)->node->setDevice(text(env, json));
}

JNIEXPORT void JNICALL CONNECT_JNI(setInterfaces)(JNIEnv *env, jclass, jlong value, jstring json) {
  handle(value)->node->setInterfaces(text(env, json));
}

JNIEXPORT void JNICALL CONNECT_JNI(publishPlayback)(JNIEnv *env, jclass, jlong value, jstring json) {
  handle(value)->node->publishPlayback(text(env, json));
}

JNIEXPORT void JNICALL CONNECT_JNI(connectTo)(JNIEnv *env, jclass, jlong value, jstring deviceId) {
  handle(value)->node->connectTo(text(env, deviceId));
}

JNIEXPORT void JNICALL CONNECT_JNI(disconnect)(JNIEnv *env, jclass, jlong value, jstring sessionId) {
  handle(value)->node->disconnect(text(env, sessionId));
}

JNIEXPORT jstring JNICALL CONNECT_JNI(command)(JNIEnv *env, jclass, jlong value, jstring json) {
  return javaString(env, handle(value)->node->command(text(env, json)));
}

JNIEXPORT jstring JNICALL CONNECT_JNI(request)(JNIEnv *env, jclass, jlong value, jstring json) {
  return javaString(env, handle(value)->node->request(text(env, json)));
}

JNIEXPORT void JNICALL CONNECT_JNI(respond)(JNIEnv *env, jclass, jlong value, jstring json) {
  handle(value)->node->respond(text(env, json));
}

JNIEXPORT jboolean JNICALL CONNECT_JNI(sendData)(JNIEnv *env, jclass, jlong value, jstring sessionId, jstring header,
                               jbyteArray payload) {
  const jsize length = payload ? env->GetArrayLength(payload) : 0;
  std::string bytes(static_cast<std::size_t>(length), '\0');
  if (length > 0)
    env->GetByteArrayRegion(payload, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
  return handle(value)->node->sendData(text(env, sessionId), text(env, header), bytes) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL CONNECT_JNI(sendStream)(JNIEnv *env, jclass, jlong value, jstring sessionId, jstring meta,
                                                  jbyteArray payload) {
  const jsize length = payload ? env->GetArrayLength(payload) : 0;
  std::string bytes(static_cast<std::size_t>(length), '\0');
  if (length > 0)
    env->GetByteArrayRegion(payload, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
  return javaString(env, handle(value)->node->sendStream(text(env, sessionId), text(env, meta), std::move(bytes)));
}

} // extern "C"
