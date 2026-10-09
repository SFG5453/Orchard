/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "network/Http.hpp"
#include "platform/windows/Wide.hpp"

#include <winhttp.h>

#include <map>
#include <memory>

namespace orchard::boot {

namespace {

using platform::narrow;
using platform::widen;

#ifndef WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3
#define WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3 0x00002000
#endif

struct Handle {
  HINTERNET h = nullptr;
  explicit Handle(HINTERNET handle = nullptr) : h(handle) {}
  Handle(Handle &&other) noexcept : h(other.h) { other.h = nullptr; }
  Handle(const Handle &) = delete;
  ~Handle() {
    if (h)
      WinHttpCloseHandle(h);
  }
};

[[noreturn]] void fail(const std::string &what) {
  const DWORD code = GetLastError();
  if (code == ERROR_WINHTTP_SECURE_FAILURE || code == ERROR_WINHTTP_SECURE_INVALID_CA ||
      code == ERROR_WINHTTP_SECURE_CERT_CN_INVALID)
    throw NetworkError("certificate verification failed: " + what);
  if (code == ERROR_WINHTTP_TIMEOUT)
    throw NetworkError("connection timed out: " + what);
  throw NetworkError(what + ": WinHTTP error " + std::to_string(code));
}

std::uint64_t parseNumber(const std::wstring &text, const std::string &url) {
  try {
    return std::stoull(text);
  } catch (const std::exception &) {
    throw NetworkError("malformed response headers from " + url);
  }
}

std::wstring queryText(HINTERNET request, DWORD info) {
  DWORD size = 0;
  WinHttpQueryHeaders(request, info, WINHTTP_HEADER_NAME_BY_INDEX, nullptr, &size, WINHTTP_NO_HEADER_INDEX);
  if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || size == 0)
    return {};
  std::wstring text(size / sizeof(wchar_t), L'\0');
  if (!WinHttpQueryHeaders(request, info, WINHTTP_HEADER_NAME_BY_INDEX, text.data(), &size, WINHTTP_NO_HEADER_INDEX))
    return {};
  text.resize(size / sizeof(wchar_t));
  return text;
}

// WinHTTP brings the system trust store, proxy settings and connection reuse
// for free; certificate checks stay at their strict defaults.
class WinHttpSession final : public HttpSession {
public:
  WinHttpSession() {
    const std::wstring agent = widen(kUserAgent);
    session_.h = WinHttpOpen(agent.c_str(), WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME,
                             WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session_.h) // Windows before 8.1
      session_.h = WinHttpOpen(agent.c_str(), WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME,
                               WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session_.h)
      fail("cannot start WinHTTP");
    WinHttpSetTimeouts(session_.h, 0, 30000, 30000, 30000);
    DWORD protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3;
    if (!WinHttpSetOption(session_.h, WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof protocols)) {
      protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
      WinHttpSetOption(session_.h, WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof protocols);
    }
  }

  void get(const std::string &url, std::uint64_t offset, HttpSink &sink, const CancelToken &cancel) override {
    const std::wstring wide = widen(url);
    URL_COMPONENTS parts{};
    parts.dwStructSize = sizeof parts;
    parts.dwHostNameLength = parts.dwUrlPathLength = parts.dwExtraInfoLength = static_cast<DWORD>(-1);
    if (!WinHttpCrackUrl(wide.c_str(), 0, 0, &parts) || parts.nScheme != INTERNET_SCHEME_HTTPS)
      throw NetworkError("refusing non-https URL " + url);
    const std::wstring host(parts.lpszHostName, parts.dwHostNameLength);
    const std::wstring path = std::wstring(parts.lpszUrlPath, parts.dwUrlPathLength) +
                              std::wstring(parts.lpszExtraInfo, parts.dwExtraInfoLength);

    const std::wstring hostKey = host + L":" + std::to_wstring(parts.nPort);
    auto connection = connections_.find(hostKey);
    if (connection == connections_.end()) {
      Handle connect(WinHttpConnect(session_.h, host.c_str(), parts.nPort, 0));
      if (!connect.h)
        fail("cannot connect to " + narrow(host));
      connection = connections_.emplace(hostKey, std::move(connect)).first;
    }
    Handle request(WinHttpOpenRequest(connection->second.h, L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER,
                                      WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE));
    if (!request.h)
      fail("cannot open request for " + url);
    std::wstring headers = L"Accept-Encoding: identity\r\n";
    if (offset > 0)
      headers += L"Range: bytes=" + std::to_wstring(offset) + L"-\r\n";
    if (!WinHttpSendRequest(request.h, headers.c_str(), static_cast<DWORD>(-1), WINHTTP_NO_REQUEST_DATA, 0, 0, 0) ||
        !WinHttpReceiveResponse(request.h, nullptr))
      fail("request for " + url + " failed");
    cancel.throwIfCancelled();

    DWORD status = 0;
    DWORD size = sizeof status;
    WinHttpQueryHeaders(request.h, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX,
                        &status, &size, WINHTTP_NO_HEADER_INDEX);
    if (status != 200 && status != 206)
      throw HttpStatusError(static_cast<int>(status), url);
    std::uint64_t start = 0;
    if (status == 206) {
      const std::wstring range = queryText(request.h, WINHTTP_QUERY_CONTENT_RANGE);
      if (range.rfind(L"bytes ", 0) != 0 || parseNumber(range.substr(6), url) != offset)
        throw NetworkError("server returned the wrong byte range for " + url);
      start = offset;
    }
    std::optional<std::uint64_t> length;
    if (const std::wstring text = queryText(request.h, WINHTTP_QUERY_CONTENT_LENGTH); !text.empty())
      length = parseNumber(text, url);
    sink.onResponse(start, length);

    std::uint64_t received = 0;
    char buffer[64 * 1024];
    while (true) {
      cancel.throwIfCancelled();
      DWORD got = 0;
      if (!WinHttpReadData(request.h, buffer, sizeof buffer, &got))
        fail("download of " + url + " failed");
      if (got == 0)
        break;
      sink.onData(buffer, got);
      received += got;
    }
    if (length && received != *length)
      throw NetworkError("download of " + url + " was truncated");
  }

private:
  Handle session_;
  std::map<std::wstring, Handle> connections_;
};

} // namespace

std::unique_ptr<HttpSession> createWinHttpSession() { return std::make_unique<WinHttpSession>(); }

} // namespace orchard::boot
