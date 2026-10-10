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

// JNI for dev.sfg.orchard.mobile.discord.DiscordNative. Rich Presence goes through the Discord
// Social SDK, which talks to the installed Discord app and needs no account linking.

#include <jni.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

using namespace std::chrono_literals;

namespace {
struct Presence {
  int type = 2;
  std::string name, details, state;
  int64_t startMs = 0, endMs = 0;
  std::string largeImage, largeText, smallImage, smallText;
  std::vector<std::pair<std::string, std::string>> buttons;
};

JavaVM *g_vm = nullptr;
jclass g_class = nullptr;
jmethodID g_onStatus = nullptr;

std::mutex g_mutex;
std::condition_variable g_wake;
std::thread g_worker;
bool g_stopping = false;
bool g_commandReady = false;
std::optional<Presence> g_command;

void notify(const std::string &message, bool ok) {
  if (!g_vm || !g_class) return;
  JNIEnv *env = nullptr;
  bool attached = false;
  if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    attached = true;
  }
  jstring text = env->NewStringUTF(message.c_str());
  env->CallStaticVoidMethod(g_class, g_onStatus, text, static_cast<jboolean>(ok));
  env->DeleteLocalRef(text);
  if (env->ExceptionCheck()) env->ExceptionClear();
  if (attached) g_vm->DetachCurrentThread();
}

discordpp::ActivityTypes activityType(int type) {
  switch (type) {
  case 0: return discordpp::ActivityTypes::Playing;
  case 3: return discordpp::ActivityTypes::Watching;
  case 5: return discordpp::ActivityTypes::Competing;
  default: return discordpp::ActivityTypes::Listening;
  }
}

discordpp::Activity toActivity(const Presence &p) {
  discordpp::Activity activity;
  activity.SetType(activityType(p.type));
  if (!p.name.empty()) activity.SetName(p.name);
  if (!p.details.empty()) activity.SetDetails(p.details);
  if (!p.state.empty()) activity.SetState(p.state);

  if (p.startMs > 0) {
    discordpp::ActivityTimestamps timestamps;
    timestamps.SetStart(static_cast<uint64_t>(p.startMs));
    if (p.endMs > p.startMs) timestamps.SetEnd(static_cast<uint64_t>(p.endMs));
    activity.SetTimestamps(timestamps);
  }

  if (!p.largeImage.empty() || !p.smallImage.empty() || !p.largeText.empty()) {
    discordpp::ActivityAssets assets;
    if (!p.largeImage.empty()) assets.SetLargeImage(p.largeImage);
    if (!p.largeText.empty()) assets.SetLargeText(p.largeText);
    if (!p.smallImage.empty()) assets.SetSmallImage(p.smallImage);
    if (!p.smallText.empty()) assets.SetSmallText(p.smallText);
    activity.SetAssets(assets);
  }

  for (const auto &[label, url] : p.buttons) {
    discordpp::ActivityButton button;
    button.SetLabel(label);
    button.SetUrl(url);
    activity.AddButton(button);
  }
  return activity;
}

void runWorker(uint64_t applicationId) {
  discordpp::Client client;
  client.SetApplicationId(applicationId);
  std::optional<Presence> latest;
  bool dirty = false;
  bool outstanding = false;
  uint64_t generation = 0;
  auto nextAttempt = std::chrono::steady_clock::now();

  while (true) {
    std::optional<Presence> command;
    bool hasCommand = false;
    {
      std::unique_lock lock(g_mutex);
      // Callbacks only fire inside RunCallbacks, so poll while work is pending.
      const bool busy = outstanding || dirty;
      auto ready = [] { return g_stopping || g_commandReady; };
      if (busy) g_wake.wait_for(lock, 100ms, ready);
      else g_wake.wait(lock, ready);
      if (g_stopping) break;
      if (g_commandReady) {
        command = std::move(g_command);
        g_command.reset();
        g_commandReady = false;
        hasCommand = true;
      }
    }

    if (hasCommand) {
      ++generation;
      latest = std::move(command);
      dirty = latest.has_value();
      if (!latest) client.ClearRichPresence();
    }

    if (latest && dirty && !outstanding && std::chrono::steady_clock::now() >= nextAttempt) {
      const uint64_t sent = generation;
      outstanding = true;
      dirty = false;
      // Discord rate limits activity updates; hold the newest one until the window opens.
      nextAttempt = std::chrono::steady_clock::now() + 5s;
      client.UpdateRichPresence(toActivity(*latest), [&, sent](discordpp::ClientResult result) {
        outstanding = false;
        if (sent != generation) return;
        if (result.Type() == discordpp::ErrorType::None) {
          notify({}, true);
        } else {
          dirty = true;
          notify(result.ToString(), false);
        }
      });
    }
    discordpp::RunCallbacks();
  }

  ++generation;
  client.ClearRichPresence();
  discordpp::RunCallbacks();
}

std::string text(JNIEnv *env, jstring value) {
  if (!value) return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string out = chars ? chars : "";
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

void post(std::optional<Presence> presence) {
  {
    std::lock_guard lock(g_mutex);
    if (g_stopping || !g_worker.joinable()) return;
    g_command = std::move(presence);
    g_commandReady = true;
  }
  g_wake.notify_one();
}
} // namespace

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_dev_sfg_orchard_mobile_discord_DiscordNative_start(
    JNIEnv *env, jclass clazz, jlong applicationId) {
  std::lock_guard lock(g_mutex);
  if (g_worker.joinable()) return;
  g_class = static_cast<jclass>(env->NewGlobalRef(clazz));
  g_onStatus = env->GetStaticMethodID(clazz, "onStatus", "(Ljava/lang/String;Z)V");
  g_stopping = false;
  g_commandReady = false;
  g_command.reset();
  g_worker = std::thread(runWorker, static_cast<uint64_t>(applicationId));
}

JNIEXPORT void JNICALL Java_dev_sfg_orchard_mobile_discord_DiscordNative_stop(JNIEnv *, jclass) {
  std::thread worker;
  {
    std::lock_guard lock(g_mutex);
    if (!g_worker.joinable()) return;
    g_stopping = true;
    worker = std::move(g_worker);
  }
  g_wake.notify_one();
  worker.join();
}

JNIEXPORT void JNICALL Java_dev_sfg_orchard_mobile_discord_DiscordNative_clearPresence(JNIEnv *, jclass) {
  post(std::nullopt);
}

JNIEXPORT void JNICALL Java_dev_sfg_orchard_mobile_discord_DiscordNative_setPresence(
    JNIEnv *env, jclass, jint type, jstring name, jstring details, jstring state, jlong startMs, jlong endMs,
    jstring largeImage, jstring largeText, jstring smallImage, jstring smallText, jobjectArray labels,
    jobjectArray urls) {
  Presence p;
  p.type = type;
  p.name = text(env, name);
  p.details = text(env, details);
  p.state = text(env, state);
  p.startMs = startMs;
  p.endMs = endMs;
  p.largeImage = text(env, largeImage);
  p.largeText = text(env, largeText);
  p.smallImage = text(env, smallImage);
  p.smallText = text(env, smallText);
  const jsize count = labels && urls ? std::min(env->GetArrayLength(labels), env->GetArrayLength(urls)) : 0;
  for (jsize i = 0; i < count; ++i) {
    auto label = static_cast<jstring>(env->GetObjectArrayElement(labels, i));
    auto url = static_cast<jstring>(env->GetObjectArrayElement(urls, i));
    p.buttons.emplace_back(text(env, label), text(env, url));
    env->DeleteLocalRef(label);
    env->DeleteLocalRef(url);
  }
  post(std::move(p));
}
}
