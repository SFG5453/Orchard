/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "util/Error.hpp"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>

namespace orchard::boot {

struct NetworkError : Error {
  using Error::Error;
};

struct HttpStatusError : NetworkError {
  HttpStatusError(int status, const std::string &url)
      : NetworkError("HTTP " + std::to_string(status) + " for " + url), status(status) {}
  int status;
};

class HttpSink {
public:
  virtual ~HttpSink() = default;
  // `offset` is where the body starts in the resource: 0 for 200, the range start for 206.
  virtual void onResponse(std::uint64_t offset, std::optional<std::uint64_t> length) = 0;
  virtual void onData(const char *data, std::size_t size) = 0;
};

// One per thread. Implementations verify certificates against the system
// trust store, follow https-only redirects and may reuse connections.
class HttpSession {
public:
  virtual ~HttpSession() = default;
  // GET `url`, asking for bytes from `offset` on when it is non-zero.
  // Throws HttpStatusError for anything but 200/206, NetworkError on I/O
  // failure and Cancelled once `cancel` fires.
  virtual void get(const std::string &url, std::uint64_t offset, HttpSink &sink, const CancelToken &cancel) = 0;
};

// Small documents (channel files, manifests) fetched whole into memory.
std::string httpGetText(HttpSession &session, const std::string &url, std::size_t limit,
                        const CancelToken &cancel);

inline constexpr const char *kUserAgent = "OrchardBootstrapper/" ORCHARD_BOOTSTRAPPER_VERSION;

} // namespace orchard::boot
