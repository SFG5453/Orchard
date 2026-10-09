/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <string_view>

// HTTP/1.1 framing for transports that speak raw sockets.
namespace orchard::boot::http {

struct Url {
  std::string host;
  std::string port = "443";
  std::string target = "/";
};

// Only https:// URLs are accepted; there is no plain-text fallback.
Url parseHttpsUrl(std::string_view url);
std::string resolveLocation(const Url &base, std::string_view location);

struct ResponseHead {
  int status = 0;
  std::map<std::string, std::string> headers; // lowercase names
  std::string header(const std::string &name) const;
};

// Returns the header block length, or 0 while it is incomplete.
std::size_t parseResponseHead(std::string_view buffer, ResponseHead &head);
// Start offset from "Content-Range: bytes X-Y/Z".
std::optional<std::uint64_t> rangeStart(const ResponseHead &head);

// Incremental Transfer-Encoding: chunked decoder.
class ChunkedDecoder {
public:
  // Appends decoded bytes from `in` to `out`; returns false on a framing error.
  bool feed(std::string_view in, std::string &out);
  bool done() const { return state_ == State::Done; }

private:
  enum class State { Size, Data, DataEnd, Trailer, Done };
  State state_ = State::Size;
  std::string line_;
  std::uint64_t remaining_ = 0;
};

} // namespace orchard::boot::http
