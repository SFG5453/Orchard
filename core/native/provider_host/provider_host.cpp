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

#include "provider_host.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <utility>

namespace orchard::provider {
namespace {

constexpr std::size_t kPlayerCacheLimit = 2 * 1024 * 1024;

// setTimeout and fetch for code that expects a browser, without the browser.
constexpr char kFetchShim[] = R"JS(
(() => {
  globalThis.setTimeout = (callback, delay = 0, ...args) =>
    __orchardSetTimer(() => callback(...args), delay, false);
  globalThis.setInterval = (callback, delay = 0, ...args) =>
    __orchardSetTimer(() => callback(...args), delay, true);
  globalThis.clearTimeout = globalThis.clearInterval = __orchardClearTimer;
  globalThis.fetch = async function(input, init = {}) {
    const raw = await globalThis.__orchardNativeFetch(String(input), init || {});
    return Object.freeze({
      ok: raw.status >= 200 && raw.status < 300,
      status: raw.status,
      statusText: raw.statusText || '',
      url: raw.url || String(input),
      redirected: Boolean(raw.redirected),
      headers: Object.freeze(raw.headers || {}),
      text: async () => raw.body,
      json: async () => JSON.parse(raw.body),
      arrayBuffer: async () => raw.bodyBuffer || new ArrayBuffer(0)
    });
  };
})();
)JS";

ProviderHost *hostOf(JSContext *context) {
  return static_cast<ProviderHost *>(JS_GetContextOpaque(context));
}

std::string toString(JSContext *context, JSValueConst value) {
  std::size_t length = 0;
  const char *text = JS_ToCStringLen(context, &length, value);
  if (!text) {
    JS_FreeValue(context, JS_GetException(context));
    return {};
  }
  std::string result(text, length);
  JS_FreeCString(context, text);
  return result;
}

std::string lower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return value;
}

bool startsWith(std::string_view value, std::string_view prefix) {
  return value.substr(0, prefix.size()) == prefix;
}

} // namespace

ProviderHost::ProviderHost(Platform &platform, Bundle bundle)
    : m_platform(platform), m_bundle(std::move(bundle)) {}

ProviderHost::~ProviderHost() {
  if (!m_context) {
    if (m_runtime) JS_FreeRuntime(m_runtime);
    return;
  }
  for (auto &[id, pending] : m_fetches) {
    JS_FreeValue(m_context, pending.resolve);
    JS_FreeValue(m_context, pending.reject);
  }
  for (auto &[id, timer] : m_timers) JS_FreeValue(m_context, timer.callback);
  JS_FreeContext(m_context);
  JS_FreeRuntime(m_runtime);
}

void ProviderHost::initialize() {
  m_initialized = true;
  m_runtime = JS_NewRuntime();
  if (!m_runtime) {
    m_initializationError = "Could not create the QuickJS runtime.";
    return;
  }
  // The large player AST lives in the native extractor's short-lived arena.
  JS_SetMemoryLimit(m_runtime, 256 * 1024 * 1024);
  JS_SetMaxStackSize(m_runtime, 2 * 1024 * 1024);
  m_context = JS_NewContext(m_runtime);
  if (!m_context) {
    m_initializationError = "Could not create the QuickJS context.";
    return;
  }
  JS_SetContextOpaque(m_context, this);

  JSValue global = JS_GetGlobalObject(m_context);
  auto define = [&](const char *name, JSCFunction *function, int length) {
    JS_SetPropertyStr(m_context, global, name, JS_NewCFunction(m_context, function, name, length));
  };
  define("__orchardNativeFetch", &ProviderHost::nativeFetch, 2);
  define("__orchardPlayerCache", &ProviderHost::playerCache, 1);
  define("__orchardExtractPlayer", &ProviderHost::extractPlayer, 1);
  define("__orchardMintPoToken", &ProviderHost::mintPoToken, 2);
  define("__orchardSetTimer", &ProviderHost::setTimer, 3);
  define("__orchardClearTimer", &ProviderHost::clearTimer, 1);
  defineMediaFunctions(global);
  JS_FreeValue(m_context, global);

  JSValue shim = JS_Eval(m_context, kFetchShim, sizeof(kFetchShim) - 1, "fetch-shim.js",
                         JS_EVAL_TYPE_GLOBAL);
  if (JS_IsException(shim)) {
    m_initializationError = takeException();
    return;
  }
  JS_FreeValue(m_context, shim);
  std::string error;
  if (!evaluateBytecode(m_bundle.file, error))
    m_initializationError =
        error.empty() ? "Could not open the embedded " + m_bundle.label + " provider bundle." : error;
}

// Bytecode is trusted build output shipped with the app, never a downloaded player.
bool ProviderHost::evaluateBytecode(std::string_view name, std::string &error) {
  std::string bytes;
  if (!m_platform.loadBundle(name, bytes)) return false;
  JSValue compiled = JS_ReadObject(m_context, reinterpret_cast<const uint8_t *>(bytes.data()),
                                   bytes.size(), JS_READ_OBJ_BYTECODE);
  if (JS_IsException(compiled)) {
    error = takeException();
    return false;
  }
  JSValue result = JS_EvalFunction(m_context, compiled); // consumes compiled
  const bool ok = !JS_IsException(result);
  if (!ok) error = takeException();
  JS_FreeValue(m_context, result);
  return ok;
}

void ProviderHost::invoke(std::uint64_t requestId, std::string_view method,
                          std::string_view payloadJson) {
  if (!m_initialized) initialize();
  if (!m_initializationError.empty()) {
    m_platform.settled(requestId, false, m_initializationError);
    return;
  }

  JSValue global = JS_GetGlobalObject(m_context);
  JSValue provider = JS_GetPropertyStr(m_context, global, m_bundle.global.c_str());
  JSValue function = JS_GetPropertyStr(m_context, provider, "invoke");
  const std::string payload(payloadJson.empty() ? std::string_view("null") : payloadJson);
  JSValue arguments[] = {
      JS_NewStringLen(m_context, method.data(), method.size()),
      JS_ParseJSON(m_context, payload.c_str(), payload.size(), "provider-payload.json"),
  };
  JSValue promise = JS_Call(m_context, function, provider, 2, arguments);
  JS_FreeValue(m_context, arguments[0]);
  JS_FreeValue(m_context, arguments[1]);
  JS_FreeValue(m_context, function);
  JS_FreeValue(m_context, provider);
  JS_FreeValue(m_context, global);

  if (JS_IsException(promise)) {
    m_platform.settled(requestId, false, takeException());
    return;
  }

  JSValue then = JS_GetPropertyStr(m_context, promise, "then");
  if (!JS_IsFunction(m_context, then)) {
    m_platform.settled(requestId, true, stringify(promise));
    JS_FreeValue(m_context, then);
    JS_FreeValue(m_context, promise);
    collectGarbageIfIdle();
    return;
  }

  JSValue data[] = {JS_NewInt64(m_context, static_cast<int64_t>(requestId))};
  JSValue callbacks[] = {
      JS_NewCFunctionData(m_context, &ProviderHost::settledCallback, 1, 0, 1, data),
      JS_NewCFunctionData(m_context, &ProviderHost::settledCallback, 1, 1, 1, data),
  };
  JS_FreeValue(m_context, data[0]);
  JS_FreeValue(m_context, JS_Call(m_context, then, promise, 2, callbacks));
  JS_FreeValue(m_context, callbacks[0]);
  JS_FreeValue(m_context, callbacks[1]);
  JS_FreeValue(m_context, then);
  JS_FreeValue(m_context, promise);
  drainJobs();
}

JSValue ProviderHost::settledCallback(JSContext *context, JSValueConst, int argc, JSValueConst *argv,
                                      int magic, JSValueConst *data) {
  auto *self = hostOf(context);
  int64_t requestId = 0;
  JS_ToInt64(context, &requestId, data[0]);
  if (magic == 0) {
    if (argc > 0 && self->settleBytes(static_cast<std::uint64_t>(requestId), argv[0]))
      return JS_UNDEFINED;
    self->m_platform.settled(static_cast<std::uint64_t>(requestId), true,
                             argc > 0 ? self->stringify(argv[0]) : std::string("null"));
    return JS_UNDEFINED;
  }
  std::string message = argc > 0 ? toString(context, argv[0]) : std::string();
  if (argc > 0 && JS_IsObject(argv[0])) {
    JSValue property = JS_GetPropertyStr(context, argv[0], "message");
    std::string detail = JS_IsUndefined(property) ? std::string() : toString(context, property);
    JS_FreeValue(context, property);
    if (!detail.empty()) message = std::move(detail);
  }
  self->m_platform.settled(static_cast<std::uint64_t>(requestId), false,
                           message.empty() ? self->m_bundle.label + " provider request failed." : message);
  return JS_UNDEFINED;
}

JSValue ProviderHost::nativeFetch(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  auto *self = hostOf(context);
  if (argc < 1) return JS_ThrowTypeError(context, "fetch requires a URL");
  FetchRequest request;
  request.url = toString(context, argv[0]);
  const std::string scheme = lower(request.url.substr(0, 8));
  if (!startsWith(scheme, "https://") && !startsWith(scheme, "http://"))
    return JS_ThrowTypeError(context, "fetch URL must use HTTP or HTTPS");

  request.method = "GET";
  if (argc > 1 && JS_IsObject(argv[1])) {
    JSValue method = JS_GetPropertyStr(context, argv[1], "method");
    if (JS_IsString(method)) {
      request.method = toString(context, method);
      std::transform(request.method.begin(), request.method.end(), request.method.begin(),
                     [](unsigned char c) { return static_cast<char>(std::toupper(c)); });
    }
    JS_FreeValue(context, method);
    JSValue body = JS_GetPropertyStr(context, argv[1], "body");
    if (JS_IsString(body)) request.body = toString(context, body);
    JS_FreeValue(context, body);
    JSValue headers = JS_GetPropertyStr(context, argv[1], "headers");
    JSPropertyEnum *properties = nullptr;
    uint32_t count = 0;
    if (JS_IsObject(headers) &&
        JS_GetOwnPropertyNames(context, &properties, &count, headers,
                               JS_GPN_STRING_MASK | JS_GPN_ENUM_ONLY) == 0) {
      for (uint32_t i = 0; i < count; ++i) {
        JSValue value = JS_GetProperty(context, headers, properties[i].atom);
        if (!JS_IsUndefined(value) && !JS_IsFunction(context, value)) {
          const char *name = JS_AtomToCString(context, properties[i].atom);
          if (name) {
            request.headers.emplace_back(name, JS_IsNull(value) ? std::string()
                                                                : toString(context, value));
            JS_FreeCString(context, name);
          }
        }
        JS_FreeValue(context, value);
      }
      JS_FreePropertyEnum(context, properties, count);
    }
    JS_FreeValue(context, headers);
  }

  JSValue resolving[2];
  JSValue promise = JS_NewPromiseCapability(context, resolving);
  if (JS_IsException(promise)) return promise;
  request.id = ++self->m_nextFetchId;
  self->m_fetches.emplace(request.id, PendingFetch{resolving[0], resolving[1], request.url});
  self->m_platform.fetch(std::move(request));
  return promise;
}

void ProviderHost::completeFetch(std::uint64_t fetchId, const FetchResponse &response) {
  auto found = m_fetches.find(fetchId);
  if (found == m_fetches.end() || !m_context) return;
  const PendingFetch pending = found->second;
  m_fetches.erase(found);

  if (response.status == 0) {
    JSValue error = JS_NewError(m_context);
    JS_SetPropertyStr(m_context, error, "message",
                      JS_NewStringLen(m_context, response.error.data(), response.error.size()));
    JS_FreeValue(m_context, JS_Call(m_context, pending.reject, JS_UNDEFINED, 1, &error));
    JS_FreeValue(m_context, error);
  } else {
    JSValue value = JS_NewObject(m_context);
    auto setString = [&](const char *name, const std::string &text) {
      JS_SetPropertyStr(m_context, value, name, JS_NewStringLen(m_context, text.data(), text.size()));
    };
    JS_SetPropertyStr(m_context, value, "status", JS_NewInt32(m_context, response.status));
    setString("statusText", response.statusText);
    setString("url", response.url.empty() ? pending.originalUrl : response.url);
    JS_SetPropertyStr(m_context, value, "redirected", JS_NewBool(m_context, response.redirected));
    setString("body", response.body);
    const std::string type = lower(response.contentType);
    if (!startsWith(type, "application/json") && !startsWith(type, "text/"))
      JS_SetPropertyStr(m_context, value, "bodyBuffer",
                        JS_NewArrayBufferCopy(m_context,
                                              reinterpret_cast<const uint8_t *>(response.body.data()),
                                              response.body.size()));
    JSValue headers = JS_NewObject(m_context);
    for (const auto &[name, text] : response.headers)
      JS_SetPropertyStr(m_context, headers, lower(name).c_str(),
                        JS_NewStringLen(m_context, text.data(), text.size()));
    JS_SetPropertyStr(m_context, value, "headers", headers);
    JS_FreeValue(m_context, JS_Call(m_context, pending.resolve, JS_UNDEFINED, 1, &value));
    JS_FreeValue(m_context, value);
  }
  JS_FreeValue(m_context, pending.resolve);
  JS_FreeValue(m_context, pending.reject);
  drainJobs();
  collectGarbageIfIdle();
}

JSValue ProviderHost::setTimer(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  auto *self = hostOf(context);
  if (argc < 1 || !JS_IsFunction(context, argv[0]))
    return JS_ThrowTypeError(context, "Timer callback must be a function");
  double delay = 0;
  if (argc > 1 && JS_ToFloat64(context, &delay, argv[1]) < 0) return JS_EXCEPTION;
  const bool repeat = argc > 2 && JS_ToBool(context, argv[2]);
  const int id = ++self->m_nextTimerId;
  self->m_timers.emplace(id, PendingTimer{JS_DupValue(context, argv[0]), repeat});
  self->m_platform.startTimer(id, delay > 0 ? static_cast<int>(std::min(delay, 2147483647.0)) : 0,
                              repeat);
  return JS_NewInt32(context, id);
}

JSValue ProviderHost::clearTimer(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  auto *self = hostOf(context);
  int32_t id = 0;
  if (argc && JS_ToInt32(context, &id, argv[0]) < 0) return JS_EXCEPTION;
  auto found = self->m_timers.find(id);
  if (found != self->m_timers.end()) {
    self->m_platform.stopTimer(id);
    JS_FreeValue(context, found->second.callback);
    self->m_timers.erase(found);
  }
  return JS_UNDEFINED;
}

void ProviderHost::fireTimer(int timerId) {
  auto found = m_timers.find(timerId);
  if (found == m_timers.end() || !m_context) return;
  JSValue callback = JS_DupValue(m_context, found->second.callback);
  if (!found->second.repeat) {
    JS_FreeValue(m_context, found->second.callback);
    m_timers.erase(found);
  }
  JSValue result = JS_Call(m_context, callback, JS_UNDEFINED, 0, nullptr);
  if (JS_IsException(result)) takeException();
  JS_FreeValue(m_context, result);
  JS_FreeValue(m_context, callback);
  drainJobs();
}

JSValue ProviderHost::mintPoToken(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  auto *self = hostOf(context);
  if (!self->m_poMinterLoaded) {
    // Keep DOM compatibility code out of startup and catalog-only sessions.
    // Still QuickJS: no browser engine has sneaked in wearing a fake mustache.
    std::string error;
    if (!self->evaluateBytecode("youtube-po-minter.qjc", error))
      return JS_ThrowInternalError(context, "%s",
                                   error.empty() ? "Could not load the YouTube PO minter" : error.c_str());
    self->m_poMinterLoaded = true;
  }
  JSValue global = JS_GetGlobalObject(context);
  JSValue service = JS_GetPropertyStr(context, global, "OrchardYouTubePoToken");
  JS_FreeValue(context, global);
  if (argc > 1 && JS_ToBool(context, argv[1])) {
    JSValue invalidate = JS_GetPropertyStr(context, service, "invalidate");
    JSValue result = JS_Call(context, invalidate, service, 0, nullptr);
    JS_FreeValue(context, invalidate);
    if (JS_IsException(result)) {
      JS_FreeValue(context, service);
      return result;
    }
    JS_FreeValue(context, result);
  }
  JSValue get = JS_GetPropertyStr(context, service, "get");
  JSValue result = JS_Call(context, get, service, std::min(argc, 1), argv);
  JS_FreeValue(context, get);
  JS_FreeValue(context, service);
  return result;
}

// One bounded cache entry for public YouTube player code. No account data or
// caller-supplied paths cross this bridge.
JSValue ProviderHost::playerCache(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  auto *self = hostOf(context);
  if (argc && !JS_IsUndefined(argv[0])) {
    const std::string data = toString(context, argv[0]);
    if (data.size() > kPlayerCacheLimit) return JS_FALSE;
    return JS_NewBool(context, self->m_platform.writePlayerCache(data));
  }
  std::string data;
  if (!self->m_platform.readPlayerCache(data) || data.size() > kPlayerCacheLimit) return JS_NULL;
  return JS_NewStringLen(context, data.data(), data.size());
}

JSValue ProviderHost::extractPlayer(JSContext *context, JSValueConst, int argc, JSValueConst *argv) {
  auto *self = hostOf(context);
  if (argc != 1 || !JS_IsString(argv[0]))
    return JS_ThrowTypeError(context, "Player extraction requires source text");
  std::size_t length = 0;
  const char *source = JS_ToCStringLen(context, &length, argv[0]);
  if (!source) return JS_EXCEPTION;
  const std::string json = self->m_platform.extractPlayer(std::string_view(source, length));
  JS_FreeCString(context, source);
  if (json.empty()) return JS_ThrowInternalError(context, "Native player extraction failed");
  return JS_ParseJSON(context, json.data(), json.size(), "player-extraction.json");
}

std::string ProviderHost::stringify(JSValueConst value) {
  if (JS_IsUndefined(value) || JS_IsNull(value)) return "null";
  JSValue encoded = JS_JSONStringify(m_context, value, JS_UNDEFINED, JS_UNDEFINED);
  std::string json;
  if (JS_IsException(encoded)) takeException();
  else if (JS_IsString(encoded)) json = toString(m_context, encoded);
  JS_FreeValue(m_context, encoded);
  return json.empty() ? "null" : json;
}

std::string ProviderHost::takeException() {
  JSValue exception = JS_GetException(m_context);
  std::string message = toString(m_context, exception);
  if (JS_IsObject(exception)) {
    JSValue stack = JS_GetPropertyStr(m_context, exception, "stack");
    if (!JS_IsUndefined(stack)) {
      std::string text = toString(m_context, stack);
      if (!text.empty()) message = std::move(text);
    }
    JS_FreeValue(m_context, stack);
  }
  JS_FreeValue(m_context, exception);
  return message.empty() ? "QuickJS evaluation failed." : message;
}

void ProviderHost::drainJobs() {
  JSContext *jobContext = nullptr;
  while (m_runtime && JS_ExecutePendingJob(m_runtime, &jobContext) > 0) {
  }
}

void ProviderHost::collectGarbageIfIdle() {
  if (m_runtime && m_fetches.empty() && m_timers.empty()) JS_RunGC(m_runtime);
}

} // namespace orchard::provider
