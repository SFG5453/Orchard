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

#include <cstdint>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

extern "C" {
#include <quickjs.h>
}

// Qt-free QuickJS host for embedded provider bytecode. Desktop and Android
// supply networking, timers and storage; the JS contract lives here once.
namespace orchard::provider {

using Headers = std::vector<std::pair<std::string, std::string>>;

// An embedded bytecode bundle and the global whose invoke(method, payload) it exposes.
struct Bundle {
  std::string file;
  std::string global;
  std::string label; // Names the provider in generic failure messages.
};

Bundle youtubeBundle();
Bundle qobuzBundle();

enum class Cipher { Aes128CbcDecrypt, Aes128Ctr };

struct FetchRequest {
  std::uint64_t id = 0;
  std::string url;
  std::string method;
  std::string body;
  Headers headers;
};

struct FetchResponse {
  int status = 0; // 0 means transport failure; `error` explains it.
  std::string error;
  std::string statusText;
  std::string url;
  bool redirected = false;
  std::string contentType;
  std::string body;
  Headers headers;
};

// Called only on the host thread. Completions must be delivered back on it.
class Platform {
public:
  virtual ~Platform() = default;
  // Cookies stay manual: the provider signs the exact cookie snapshot it sends.
  virtual void fetch(FetchRequest request) = 0;
  virtual void startTimer(int id, int delayMs, bool repeat) = 0;
  virtual void stopTimer(int id) = 0;
  virtual bool loadBundle(std::string_view name, std::string &bytes) = 0;
  virtual bool readPlayerCache(std::string &data) = 0;
  virtual bool writePlayerCache(std::string_view data) = 0;
  // Returns the extractor JSON, or an empty string on failure.
  virtual std::string extractPlayer(std::string_view source) = 0;
  virtual void settled(std::uint64_t requestId, bool ok, std::string result) = 0;
  // A top-level Uint8Array or ArrayBuffer result. Hosts without a binary
  // channel report it as a failure.
  virtual void settledBytes(std::uint64_t requestId, std::string bytes) {
    (void)bytes;
    settled(requestId, false, "This host cannot receive binary provider results.");
  }
  // Media crypto for segmented streams. The defaults leave it unavailable.
  virtual bool hkdfSha256(std::string_view key, std::string_view salt, std::string_view info,
                          std::size_t length, std::string &out) {
    (void)key, (void)salt, (void)info, (void)length, (void)out;
    return false;
  }
  virtual bool aes128(Cipher cipher, std::string_view key, std::string_view iv,
                      std::string_view data, std::string &out) {
    (void)cipher, (void)key, (void)iv, (void)data, (void)out;
    return false;
  }
};

// The host thread needs a stack above QuickJS's 2 MiB limit; 8 MiB is used.
// Stop fetch and timer delivery before destroying the host.
class ProviderHost {
public:
  explicit ProviderHost(Platform &platform, Bundle bundle = youtubeBundle());
  ~ProviderHost();
  ProviderHost(const ProviderHost &) = delete;
  ProviderHost &operator=(const ProviderHost &) = delete;

  // Payload and success results are JSON; failures carry a message.
  void invoke(std::uint64_t requestId, std::string_view method, std::string_view payloadJson);
  void completeFetch(std::uint64_t fetchId, const FetchResponse &response);
  void fireTimer(int timerId);

private:
  struct PendingFetch {
    JSValue resolve;
    JSValue reject;
    std::string originalUrl;
  };
  struct PendingTimer {
    JSValue callback;
    bool repeat;
  };

  void initialize();
  bool evaluateBytecode(std::string_view name, std::string &error);
  std::string takeException();
  std::string stringify(JSValueConst value);
  void drainJobs();
  void collectGarbageIfIdle();

  static JSValue nativeFetch(JSContext *, JSValueConst, int, JSValueConst *);
  static JSValue playerCache(JSContext *, JSValueConst, int, JSValueConst *);
  static JSValue extractPlayer(JSContext *, JSValueConst, int, JSValueConst *);
  static JSValue mintPoToken(JSContext *, JSValueConst, int, JSValueConst *);
  static JSValue setTimer(JSContext *, JSValueConst, int, JSValueConst *);
  static JSValue clearTimer(JSContext *, JSValueConst, int, JSValueConst *);
  static JSValue settledCallback(JSContext *, JSValueConst, int, JSValueConst *, int, JSValueConst *);
  // provider_host_media.cpp: binary results and media crypto.
  void defineMediaFunctions(JSValueConst global);
  bool settleBytes(std::uint64_t requestId, JSValueConst value);
  static JSValue hkdfSha256(JSContext *, JSValueConst, int, JSValueConst *);
  static JSValue aes128(JSContext *, JSValueConst, int, JSValueConst *);

  Platform &m_platform;
  Bundle m_bundle;
  JSRuntime *m_runtime{nullptr};
  JSContext *m_context{nullptr};
  std::unordered_map<std::uint64_t, PendingFetch> m_fetches;
  std::unordered_map<int, PendingTimer> m_timers;
  std::uint64_t m_nextFetchId{0};
  int m_nextTimerId{0};
  bool m_initialized{false};
  bool m_poMinterLoaded{false};
  std::string m_initializationError;
};

} // namespace orchard::provider
