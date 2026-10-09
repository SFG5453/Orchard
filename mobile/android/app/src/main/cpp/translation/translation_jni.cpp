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

#include <jni.h>

#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "translation/litert_api.h"
#include "translation/lyric_language.h"
#include "translation/marian_model.h"
#include "translation/translation_pack_table.h"

// Android side of the shared lyric translator. Text crosses as UTF-8 byte arrays because JNI's
// own string helpers speak modified UTF-8, which mangles emoji in lyrics.
namespace {

// Process-wide so cancelling never touches a model the worker thread may be closing.
std::atomic_int g_generation{0};

std::string bytes(JNIEnv *env, jbyteArray value) {
  if (!value)
    return {};
  const jsize length = env->GetArrayLength(value);
  std::string out(size_t(length), '\0');
  env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(out.data()));
  return out;
}

jbyteArray toBytes(JNIEnv *env, const std::string &value) {
  jbyteArray out = env->NewByteArray(jsize(value.size()));
  env->SetByteArrayRegion(out, 0, jsize(value.size()), reinterpret_cast<const jbyte *>(value.data()));
  return out;
}

// Table strings are ASCII literals; codes and names never need escaping beyond quotes.
std::string quoted(std::string_view value) {
  std::string out = "\"";
  for (const char c : value) {
    if (c == '"' || c == '\\')
      out += '\\';
    out += c;
  }
  return out + '"';
}

void throwState(JNIEnv *env, const std::string &message) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  env->ThrowNew(type, message.c_str());
  env->DeleteLocalRef(type);
}

MarianModel *model(jlong handle) { return reinterpret_cast<MarianModel *>(handle); }

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL Java_dev_sfg_orchard_mobile_lyrics_translation_TranslationNative_packTable(JNIEnv *env,
                                                                                                        jclass) {
  std::string json = "[";
  for (const TranslationPack &pack : translationPackTable()) {
    if (json.size() > 1)
      json += ',';
    json += "{\"id\":" + quoted(pack.id) + ",\"source\":" + quoted(pack.source) +
            ",\"name\":" + quoted(lyric_language::displayName(pack.source)) + ",\"quality\":" + quoted(pack.quality) +
            ",\"baseUrl\":" + quoted(pack.baseUrl) + ",\"revision\":" + quoted(pack.revision) +
            ",\"credit\":" + quoted(pack.credit) + ",\"files\":[";
    for (size_t i = 0; i < std::size(pack.files); ++i) {
      const TranslationPackFile &file = pack.files[i];
      json += (i ? ",{\"name\":" : "{\"name\":") + quoted(file.name) + ",\"size\":" + std::to_string(file.size) +
              ",\"sha256\":" + quoted(file.sha256) + '}';
    }
    json += "]}";
  }
  return toBytes(env, json + ']');
}

// {"source":"kor","name":"Korean","translate":[true,false,...]}; source is empty for English songs.
JNIEXPORT jbyteArray JNICALL Java_dev_sfg_orchard_mobile_lyrics_translation_TranslationNative_plan(JNIEnv *env, jclass,
                                                                                                   jobjectArray lines) {
  std::vector<std::string> texts;
  const jsize count = lines ? env->GetArrayLength(lines) : 0;
  texts.reserve(size_t(count));
  for (jsize i = 0; i < count; ++i) {
    auto *line = static_cast<jbyteArray>(env->GetObjectArrayElement(lines, i));
    texts.push_back(bytes(env, line));
    env->DeleteLocalRef(line);
  }
  const lyric_language::SongPlan plan = lyric_language::plan(texts);
  std::string json = "{\"source\":" + quoted(plan.source) +
                     ",\"name\":" + quoted(lyric_language::displayName(plan.source)) + ",\"translate\":[";
  for (size_t i = 0; i < plan.translate.size(); ++i)
    json += (i ? "," : "") + std::string(plan.translate[i] ? "true" : "false");
  return toBytes(env, json + "]}");
}

JNIEXPORT jlong JNICALL Java_dev_sfg_orchard_mobile_lyrics_translation_TranslationNative_open(JNIEnv *env, jclass,
                                                                                              jstring runtime,
                                                                                              jbyteArray directory,
                                                                                              jint threads) {
  const char *chars = env->GetStringUTFChars(runtime, nullptr);
  const std::string library = chars ? chars : "";
  env->ReleaseStringUTFChars(runtime, chars);
  std::string error;
  const LiteRtApi *api = LiteRtApi::get(library, &error);
  if (!api) {
    throwState(env, error);
    return 0;
  }
  auto opened = std::make_unique<MarianModel>();
  if (!opened->load(*api, bytes(env, directory), threads, &error)) {
    throwState(env, error);
    return 0;
  }
  return reinterpret_cast<jlong>(opened.release());
}

// Null when cancelled or when the model fails; an empty array when it has nothing to say.
JNIEXPORT jbyteArray JNICALL Java_dev_sfg_orchard_mobile_lyrics_translation_TranslationNative_translate(
    JNIEnv *env, jclass, jlong handle, jbyteArray text, jint generation) {
  MarianModel *open = model(handle);
  if (!open)
    return nullptr;
  bool stopped = false;
  const std::string out = open->translate(bytes(env, text), [generation, &stopped] {
    stopped = g_generation.load(std::memory_order_relaxed) != generation;
    return stopped;
  });
  return stopped ? nullptr : toBytes(env, out);
}

// Any thread: translate() calls for other generations stop at their next token.
JNIEXPORT void JNICALL Java_dev_sfg_orchard_mobile_lyrics_translation_TranslationNative_setGeneration(JNIEnv *, jclass,
                                                                                                       jint generation) {
  g_generation.store(generation, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL Java_dev_sfg_orchard_mobile_lyrics_translation_TranslationNative_close(JNIEnv *, jclass,
                                                                                              jlong handle) {
  delete model(handle);
}

} // extern "C"
