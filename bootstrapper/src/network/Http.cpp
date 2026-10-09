/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "network/Http.hpp"

namespace orchard::boot {

std::string httpGetText(HttpSession &session, const std::string &url, std::size_t limit,
                        const CancelToken &cancel) {
  struct TextSink : HttpSink {
    std::string body;
    std::size_t limit;
    const std::string *url;
    void onResponse(std::uint64_t, std::optional<std::uint64_t> length) override {
      if (length && *length > limit)
        throw NetworkError(*url + " is larger than expected");
    }
    void onData(const char *data, std::size_t size) override {
      if (body.size() + size > limit)
        throw NetworkError(*url + " is larger than expected");
      body.append(data, size);
    }
  } sink;
  sink.limit = limit;
  sink.url = &url;
  session.get(url, 0, sink, cancel);
  return std::move(sink.body);
}

} // namespace orchard::boot
