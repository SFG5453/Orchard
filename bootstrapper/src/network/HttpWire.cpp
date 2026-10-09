/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "network/HttpWire.hpp"

#include "network/Http.hpp"

#include <algorithm>
#include <cctype>
#include <charconv>

namespace orchard::boot::http {

namespace {

std::string lower(std::string_view text) {
  std::string out(text);
  std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) { return std::tolower(c); });
  return out;
}

std::string_view trim(std::string_view text) {
  while (!text.empty() && (text.front() == ' ' || text.front() == '\t'))
    text.remove_prefix(1);
  while (!text.empty() && (text.back() == ' ' || text.back() == '\t' || text.back() == '\r'))
    text.remove_suffix(1);
  return text;
}

} // namespace

Url parseHttpsUrl(std::string_view url) {
  if (url.size() < 9 || lower(url.substr(0, 8)) != "https://")
    throw NetworkError("refusing non-https URL " + std::string(url));
  url.remove_prefix(8);
  url = url.substr(0, url.find('#'));
  const std::size_t slash = std::min(url.find_first_of("/?"), url.size());
  std::string_view authority = url.substr(0, slash);
  Url out;
  if (slash < url.size())
    out.target = url[slash] == '/' ? std::string(url.substr(slash)) : "/" + std::string(url.substr(slash));
  if (authority.empty() || authority.find('@') != std::string_view::npos)
    throw NetworkError("malformed URL https://" + std::string(url));
  std::size_t colon = authority.rfind(':');
  if (authority.front() == '[') {
    const std::size_t close = authority.find(']');
    if (close == std::string_view::npos)
      throw NetworkError("malformed URL host");
    out.host = std::string(authority.substr(1, close - 1));
    colon = close + 1 < authority.size() && authority[close + 1] == ':' ? close + 1 : std::string_view::npos;
  } else {
    out.host = std::string(authority.substr(0, colon));
  }
  if (colon != std::string_view::npos) {
    out.port = std::string(authority.substr(colon + 1));
    if (out.port.empty() || !std::all_of(out.port.begin(), out.port.end(), ::isdigit))
      throw NetworkError("malformed URL port");
  }
  return out;
}

std::string resolveLocation(const Url &base, std::string_view location) {
  const std::string origin =
      "https://" + (base.host.find(':') != std::string::npos ? "[" + base.host + "]" : base.host) +
      (base.port == "443" ? "" : ":" + base.port);
  if (lower(location.substr(0, 8)) == "https://" || lower(location.substr(0, 7)) == "http://")
    return std::string(location);
  if (location.starts_with("//"))
    return "https:" + std::string(location);
  if (location.starts_with("/"))
    return origin + std::string(location);
  const std::string path = base.target.substr(0, base.target.find('?'));
  return origin + path.substr(0, path.rfind('/') + 1) + std::string(location);
}

std::string ResponseHead::header(const std::string &name) const {
  const auto it = headers.find(name);
  return it == headers.end() ? std::string() : it->second;
}

std::size_t parseResponseHead(std::string_view buffer, ResponseHead &head) {
  const std::size_t end = buffer.find("\r\n\r\n");
  if (end == std::string_view::npos) {
    if (buffer.size() > 64 * 1024)
      throw NetworkError("HTTP response header is too large");
    return 0;
  }
  std::string_view block = buffer.substr(0, end);
  const std::size_t lineEnd = std::min(block.find("\r\n"), block.size());
  const std::string_view status = block.substr(0, lineEnd);
  if (!status.starts_with("HTTP/1.") || status.size() < 12)
    throw NetworkError("malformed HTTP status line");
  if (std::from_chars(status.data() + 9, status.data() + 12, head.status).ec != std::errc())
    throw NetworkError("malformed HTTP status code");
  head.headers.clear();
  block.remove_prefix(std::min(lineEnd + 2, block.size()));
  while (!block.empty()) {
    const std::size_t next = std::min(block.find("\r\n"), block.size());
    const std::string_view line = block.substr(0, next);
    const std::size_t colon = line.find(':');
    if (colon != std::string_view::npos) {
      std::string &value = head.headers[lower(trim(line.substr(0, colon)))];
      value += (value.empty() ? "" : ", ") + std::string(trim(line.substr(colon + 1)));
    }
    block.remove_prefix(std::min(next + 2, block.size()));
  }
  return end + 4;
}

std::optional<std::uint64_t> rangeStart(const ResponseHead &head) {
  const std::string range = head.header("content-range");
  if (!range.starts_with("bytes "))
    return std::nullopt;
  std::uint64_t start = 0;
  if (std::from_chars(range.data() + 6, range.data() + range.size(), start).ec != std::errc())
    return std::nullopt;
  return start;
}

bool ChunkedDecoder::feed(std::string_view in, std::string &out) {
  while (!in.empty() && state_ != State::Done) {
    if (state_ == State::Data) {
      const std::size_t take = static_cast<std::size_t>(std::min<std::uint64_t>(remaining_, in.size()));
      out.append(in.data(), take);
      in.remove_prefix(take);
      remaining_ -= take;
      if (remaining_ == 0)
        state_ = State::DataEnd;
      continue;
    }
    line_.push_back(in.front());
    in.remove_prefix(1);
    if (line_.size() > 4096)
      return false;
    if (!line_.ends_with("\r\n"))
      continue;
    const std::string_view line(line_.data(), line_.size() - 2);
    if (state_ == State::Size) {
      const std::string_view digits = trim(line.substr(0, line.find(';')));
      if (digits.empty() ||
          std::from_chars(digits.data(), digits.data() + digits.size(), remaining_, 16).ec != std::errc())
        return false;
      state_ = remaining_ == 0 ? State::Trailer : State::Data;
    } else if (state_ == State::DataEnd) {
      if (!line.empty())
        return false;
      state_ = State::Size;
    } else if (line.empty()) {
      state_ = State::Done;
    }
    line_.clear();
  }
  return true;
}

} // namespace orchard::boot::http
