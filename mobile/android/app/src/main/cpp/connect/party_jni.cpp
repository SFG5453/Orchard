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

// JNI for dev.sfg.orchard.mobile.social.PartyPeerNative: one WebRTC data channel to a
// Listening Party peer, on the same libdatachannel stack Connect uses.

#include "connect/event_loop.h"
#include "connect/webrtc_transport.h"
#include "jni_util.h"

#include <jni.h>

#include <memory>
#include <string>
#include <utility>

namespace {

using orchard::connect::EventLoop;
using orchard::connect::WebRtcConnectTransport;
using orchard::jni::currentEnv;
using orchard::jni::javaString;
using orchard::jni::text;

constexpr const char *kPartyChannelLabel = "orchard-party";

class PartyPeer {
public:
  PartyPeer(JNIEnv *env, jobject listener, const std::string &iceServers, bool offerer)
      : m_listener(env->NewGlobalRef(listener)), m_loop(std::make_shared<EventLoop>()) {
    jclass type = env->GetObjectClass(listener);
    m_onSignal = env->GetMethodID(type, "onSignal", "(Ljava/lang/String;)V");
    m_onOpen = env->GetMethodID(type, "onOpen", "()V");
    m_onText = env->GetMethodID(type, "onText", "(Ljava/lang/String;)V");
    m_onClosed = env->GetMethodID(type, "onClosed", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(type);

    orchard::connect::Json servers = orchard::connect::Json::parse(iceServers, nullptr, false);
    m_transport = std::make_shared<WebRtcConnectTransport>(servers, offerer, kPartyChannelLabel, false);
    orchard::connect::ConnectTransport::Callbacks callbacks;
    callbacks.opened = [this] { call(m_onOpen, nullptr); };
    callbacks.text = [this](std::string value) { call(m_onText, &value); };
    callbacks.closed = [this](std::string reason) { call(m_onClosed, &reason); };
    callbacks.signal = [this](std::string json) { call(m_onSignal, &json); };
    m_transport->bind(m_loop, std::move(callbacks));
  }

  WebRtcConnectTransport &transport() { return *m_transport; }

  // Joins the loop thread, so no callback can reach the listener afterwards.
  void shutdown(JNIEnv *env) {
    m_transport->close();
    m_loop->stop();
    m_transport.reset();
    if (m_listener)
      env->DeleteGlobalRef(m_listener);
    m_listener = nullptr;
  }

private:
  void call(jmethodID method, const std::string *argument) {
    JNIEnv *env = currentEnv();
    if (!env || !m_listener)
      return;
    if (argument) {
      jstring value = javaString(env, *argument);
      env->CallVoidMethod(m_listener, method, value);
      env->DeleteLocalRef(value);
    } else {
      env->CallVoidMethod(m_listener, method);
    }
    // A Kotlin exception must not unwind into the loop thread.
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
  }

  jobject m_listener = nullptr;
  jmethodID m_onSignal = nullptr;
  jmethodID m_onOpen = nullptr;
  jmethodID m_onText = nullptr;
  jmethodID m_onClosed = nullptr;
  std::shared_ptr<EventLoop> m_loop;
  std::shared_ptr<WebRtcConnectTransport> m_transport;
};

PartyPeer *peer(jlong value) { return reinterpret_cast<PartyPeer *>(value); }

} // namespace

#define PARTY_JNI(name) Java_dev_sfg_orchard_mobile_social_PartyPeerNative_##name

extern "C" {

JNIEXPORT jlong JNICALL PARTY_JNI(create)(JNIEnv *env, jclass, jobject listener, jstring iceServers, jboolean offerer) {
  return reinterpret_cast<jlong>(new PartyPeer(env, listener, text(env, iceServers), offerer == JNI_TRUE));
}

JNIEXPORT void JNICALL PARTY_JNI(destroy)(JNIEnv *env, jclass, jlong value) {
  PartyPeer *target = peer(value);
  if (!target)
    return;
  target->shutdown(env);
  delete target;
}

JNIEXPORT void JNICALL PARTY_JNI(start)(JNIEnv *, jclass, jlong value) { peer(value)->transport().start(); }

JNIEXPORT void JNICALL PARTY_JNI(signal)(JNIEnv *env, jclass, jlong value, jstring json) {
  auto data = orchard::connect::Json::parse(text(env, json), nullptr, false);
  if (data.is_object())
    peer(value)->transport().applySignal(data);
}

JNIEXPORT jboolean JNICALL PARTY_JNI(sendText)(JNIEnv *env, jclass, jlong value, jstring message) {
  return peer(value)->transport().sendText(text(env, message)) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
