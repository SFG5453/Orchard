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

#include <dlfcn.h>
#include <jni.h>

#include <memory>
#include <string>
#include <string_view>
#include <utility>

#include "provider_host/provider_host.h"

// Android side of the shared provider host. Kotlin owns the host thread and
// delivers fetch and timer completions back on it.
namespace {

using orchard::provider::FetchRequest;
using orchard::provider::FetchResponse;
using orchard::provider::ProviderHost;

// JNI's own UTF helpers speak modified UTF-8, so convert through UTF-16.
jstring toJava(JNIEnv *env, std::string_view text) {
  std::u16string out;
  out.reserve(text.size());
  for (std::size_t i = 0; i < text.size();) {
    const auto c = static_cast<unsigned char>(text[i]);
    char32_t point = 0xFFFD;
    std::size_t length = 1;
    if (c < 0x80) point = c;
    else if ((c >> 5) == 0x6) { point = c & 0x1F; length = 2; }
    else if ((c >> 4) == 0xE) { point = c & 0x0F; length = 3; }
    else if ((c >> 3) == 0x1E) { point = c & 0x07; length = 4; }
    else length = 0;
    if (length == 0 || i + length > text.size()) {
      out += u'�';
      ++i;
      continue;
    }
    for (std::size_t k = 1; k < length; ++k) point = (point << 6) | (text[i + k] & 0x3F);
    i += length;
    if (point >= 0x10000) {
      point -= 0x10000;
      out += static_cast<char16_t>(0xD800 + (point >> 10));
      out += static_cast<char16_t>(0xDC00 + (point & 0x3FF));
    } else {
      out += static_cast<char16_t>(point);
    }
  }
  return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

std::string fromJava(JNIEnv *env, jstring value) {
  if (!value) return {};
  const jsize length = env->GetStringLength(value);
  std::u16string text(static_cast<std::size_t>(length), u'\0');
  env->GetStringRegion(value, 0, length, reinterpret_cast<jchar *>(text.data()));
  std::string out;
  out.reserve(text.size());
  for (std::size_t i = 0; i < text.size(); ++i) {
    char32_t point = text[i];
    if (point >= 0xD800 && point < 0xDC00 && i + 1 < text.size() &&
        text[i + 1] >= 0xDC00 && text[i + 1] < 0xE000) {
      point = 0x10000 + ((point - 0xD800) << 10) + (text[++i] - 0xDC00);
    }
    if (point < 0x80) {
      out += static_cast<char>(point);
    } else if (point < 0x800) {
      out += static_cast<char>(0xC0 | (point >> 6));
      out += static_cast<char>(0x80 | (point & 0x3F));
    } else if (point < 0x10000) {
      out += static_cast<char>(0xE0 | (point >> 12));
      out += static_cast<char>(0x80 | ((point >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (point & 0x3F));
    } else {
      out += static_cast<char>(0xF0 | (point >> 18));
      out += static_cast<char>(0x80 | ((point >> 12) & 0x3F));
      out += static_cast<char>(0x80 | ((point >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (point & 0x3F));
    }
  }
  return out;
}

jbyteArray toBytes(JNIEnv *env, std::string_view data) {
  jbyteArray array = env->NewByteArray(static_cast<jsize>(data.size()));
  if (array) env->SetByteArrayRegion(array, 0, static_cast<jsize>(data.size()),
                                     reinterpret_cast<const jbyte *>(data.data()));
  return array;
}

std::string fromBytes(JNIEnv *env, jbyteArray data) {
  if (!data) return {};
  std::string out(static_cast<std::size_t>(env->GetArrayLength(data)), '\0');
  env->GetByteArrayRegion(data, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte *>(out.data()));
  return out;
}

// The extractor lives in the Rust library; unit tests on the build JVM run without it.
std::string extractWithRust(std::string_view source) {
  using Extract = char *(*)(const uint8_t *, std::size_t);
  using Release = void (*)(char *);
  static void *library = dlopen("liborchard_earmark.so", RTLD_NOW);
  static auto extract = library ? reinterpret_cast<Extract>(dlsym(library, "orchard_youtube_extract")) : nullptr;
  static auto release = library ? reinterpret_cast<Release>(dlsym(library, "orchard_youtube_extract_free")) : nullptr;
  if (!extract || !release) return {};
  char *json = extract(reinterpret_cast<const uint8_t *>(source.data()), source.size());
  if (!json) return {};
  std::string result(json);
  release(json);
  return result;
}

class JniPlatform final : public orchard::provider::Platform {
public:
  JniPlatform(JNIEnv *env, jobject owner, orchard::provider::Bundle bundle)
      : m_owner(env->NewGlobalRef(owner)), m_host(*this, std::move(bundle)) {
    env->GetJavaVM(&m_vm);
    jclass type = env->GetObjectClass(owner);
    m_fetch = env->GetMethodID(type, "fetch", "(JLjava/lang/String;Ljava/lang/String;[B[Ljava/lang/String;)V");
    m_startTimer = env->GetMethodID(type, "startTimer", "(IIZ)V");
    m_stopTimer = env->GetMethodID(type, "stopTimer", "(I)V");
    m_loadBundle = env->GetMethodID(type, "loadBundle", "(Ljava/lang/String;)[B");
    m_readCache = env->GetMethodID(type, "readPlayerCache", "()[B");
    m_writeCache = env->GetMethodID(type, "writePlayerCache", "([B)Z");
    m_settled = env->GetMethodID(type, "settled", "(JZ[B)V");
    m_settledBytes = env->GetMethodID(type, "settledBytes", "(J[B)V");
    m_hkdf = env->GetMethodID(type, "hkdfSha256", "([B[B[BI)[B");
    m_aes = env->GetMethodID(type, "aes128", "(I[B[B[B)[B");
    env->DeleteLocalRef(type);
  }

  ~JniPlatform() override {
    if (JNIEnv *jni = env()) jni->DeleteGlobalRef(m_owner);
  }

  ProviderHost &host() { return m_host; }

  void fetch(FetchRequest request) override {
    JNIEnv *jni = env();
    jclass stringType = jni->FindClass("java/lang/String");
    jobjectArray headers = jni->NewObjectArray(static_cast<jsize>(request.headers.size() * 2), stringType, nullptr);
    jsize index = 0;
    for (const auto &[name, value] : request.headers) {
      jstring javaName = toJava(jni, name);
      jstring javaValue = toJava(jni, value);
      jni->SetObjectArrayElement(headers, index++, javaName);
      jni->SetObjectArrayElement(headers, index++, javaValue);
      jni->DeleteLocalRef(javaName);
      jni->DeleteLocalRef(javaValue);
    }
    jstring url = toJava(jni, request.url);
    jstring method = toJava(jni, request.method);
    jbyteArray body = toBytes(jni, request.body);
    jni->CallVoidMethod(m_owner, m_fetch, static_cast<jlong>(request.id), url, method, body, headers);
    clearException(jni);
    jni->DeleteLocalRef(url);
    jni->DeleteLocalRef(method);
    jni->DeleteLocalRef(body);
    jni->DeleteLocalRef(headers);
    jni->DeleteLocalRef(stringType);
  }

  void startTimer(int id, int delayMs, bool repeat) override {
    JNIEnv *jni = env();
    jni->CallVoidMethod(m_owner, m_startTimer, id, delayMs, static_cast<jboolean>(repeat));
    clearException(jni);
  }

  void stopTimer(int id) override {
    JNIEnv *jni = env();
    jni->CallVoidMethod(m_owner, m_stopTimer, id);
    clearException(jni);
  }

  bool loadBundle(std::string_view name, std::string &bytes) override {
    JNIEnv *jni = env();
    jstring javaName = toJava(jni, name);
    auto data = static_cast<jbyteArray>(jni->CallObjectMethod(m_owner, m_loadBundle, javaName));
    jni->DeleteLocalRef(javaName);
    if (clearException(jni) || !data) return false;
    bytes = fromBytes(jni, data);
    jni->DeleteLocalRef(data);
    return true;
  }

  bool readPlayerCache(std::string &data) override {
    JNIEnv *jni = env();
    auto value = static_cast<jbyteArray>(jni->CallObjectMethod(m_owner, m_readCache));
    if (clearException(jni) || !value) return false;
    data = fromBytes(jni, value);
    jni->DeleteLocalRef(value);
    return true;
  }

  bool writePlayerCache(std::string_view data) override {
    JNIEnv *jni = env();
    jbyteArray value = toBytes(jni, data);
    const jboolean saved = jni->CallBooleanMethod(m_owner, m_writeCache, value);
    jni->DeleteLocalRef(value);
    return !clearException(jni) && saved;
  }

  std::string extractPlayer(std::string_view source) override { return extractWithRust(source); }

  void settled(std::uint64_t requestId, bool ok, std::string result) override {
    JNIEnv *jni = env();
    jbyteArray value = toBytes(jni, result);
    jni->CallVoidMethod(m_owner, m_settled, static_cast<jlong>(requestId), static_cast<jboolean>(ok), value);
    clearException(jni);
    jni->DeleteLocalRef(value);
  }

  void settledBytes(std::uint64_t requestId, std::string bytes) override {
    JNIEnv *jni = env();
    jbyteArray value = toBytes(jni, bytes);
    jni->CallVoidMethod(m_owner, m_settledBytes, static_cast<jlong>(requestId), value);
    clearException(jni);
    jni->DeleteLocalRef(value);
  }

  bool hkdfSha256(std::string_view key, std::string_view salt, std::string_view info,
                  std::size_t length, std::string &out) override {
    JNIEnv *jni = env();
    jbyteArray javaKey = toBytes(jni, key);
    jbyteArray javaSalt = toBytes(jni, salt);
    jbyteArray javaInfo = toBytes(jni, info);
    auto result = static_cast<jbyteArray>(jni->CallObjectMethod(
        m_owner, m_hkdf, javaKey, javaSalt, javaInfo, static_cast<jint>(length)));
    jni->DeleteLocalRef(javaKey);
    jni->DeleteLocalRef(javaSalt);
    jni->DeleteLocalRef(javaInfo);
    return takeBytes(jni, result, out);
  }

  bool aes128(orchard::provider::Cipher cipher, std::string_view key, std::string_view iv,
              std::string_view data, std::string &out) override {
    JNIEnv *jni = env();
    jbyteArray javaKey = toBytes(jni, key);
    jbyteArray javaIv = toBytes(jni, iv);
    jbyteArray javaData = toBytes(jni, data);
    const jint mode = cipher == orchard::provider::Cipher::Aes128CbcDecrypt ? 0 : 1;
    auto result = static_cast<jbyteArray>(jni->CallObjectMethod(m_owner, m_aes, mode, javaKey, javaIv, javaData));
    jni->DeleteLocalRef(javaKey);
    jni->DeleteLocalRef(javaIv);
    jni->DeleteLocalRef(javaData);
    return takeBytes(jni, result, out);
  }

private:
  // A null result or a pending exception means the Kotlin side rejected the input.
  bool takeBytes(JNIEnv *jni, jbyteArray result, std::string &out) {
    if (clearException(jni) || !result) return false;
    out = fromBytes(jni, result);
    jni->DeleteLocalRef(result);
    return true;
  }

  JNIEnv *env() {
    JNIEnv *jni = nullptr;
    return m_vm && m_vm->GetEnv(reinterpret_cast<void **>(&jni), JNI_VERSION_1_6) == JNI_OK ? jni : nullptr;
  }

  // A Kotlin callback that throws must not poison the next JNI call.
  static bool clearException(JNIEnv *jni) {
    if (!jni->ExceptionCheck()) return false;
    jni->ExceptionDescribe();
    jni->ExceptionClear();
    return true;
  }

  JavaVM *m_vm{nullptr};
  jobject m_owner;
  jmethodID m_fetch, m_startTimer, m_stopTimer, m_loadBundle, m_readCache, m_writeCache, m_settled,
      m_settledBytes, m_hkdf, m_aes;
  ProviderHost m_host;
};

JniPlatform *platform(jlong handle) { return reinterpret_cast<JniPlatform *>(handle); }

} // namespace

#define PROVIDER_JNI(name) Java_dev_sfg_orchard_mobile_provider_ProviderNative_##name

extern "C" JNIEXPORT jlong JNICALL PROVIDER_JNI(create)(JNIEnv *env, jclass, jobject owner, jint bundle) {
  // The ordinal matches Kotlin's ProviderBundle.
  return reinterpret_cast<jlong>(new JniPlatform(
      env, owner, bundle == 1 ? orchard::provider::qobuzBundle() : orchard::provider::youtubeBundle()));
}

extern "C" JNIEXPORT void JNICALL PROVIDER_JNI(destroy)(JNIEnv *, jclass, jlong handle) {
  delete platform(handle);
}

extern "C" JNIEXPORT void JNICALL PROVIDER_JNI(invoke)(JNIEnv *env, jclass, jlong handle,
                                                        jlong requestId, jstring method, jbyteArray payload) {
  platform(handle)->host().invoke(static_cast<std::uint64_t>(requestId), fromJava(env, method),
                                  fromBytes(env, payload));
}

extern "C" JNIEXPORT void JNICALL PROVIDER_JNI(completeFetch)(
    JNIEnv *env, jclass, jlong handle, jlong fetchId, jint status, jstring error, jstring statusText,
    jstring url, jboolean redirected, jstring contentType, jbyteArray body, jobjectArray headers) {
  FetchResponse response;
  response.status = status;
  response.error = fromJava(env, error);
  response.statusText = fromJava(env, statusText);
  response.url = fromJava(env, url);
  response.redirected = redirected;
  response.contentType = fromJava(env, contentType);
  response.body = fromBytes(env, body);
  const jsize count = headers ? env->GetArrayLength(headers) : 0;
  for (jsize i = 0; i + 1 < count; i += 2) {
    auto name = static_cast<jstring>(env->GetObjectArrayElement(headers, i));
    auto value = static_cast<jstring>(env->GetObjectArrayElement(headers, i + 1));
    response.headers.emplace_back(fromJava(env, name), fromJava(env, value));
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(value);
  }
  platform(handle)->host().completeFetch(static_cast<std::uint64_t>(fetchId), response);
}

extern "C" JNIEXPORT void JNICALL PROVIDER_JNI(fireTimer)(JNIEnv *, jclass, jlong handle, jint id) {
  platform(handle)->host().fireTimer(id);
}
