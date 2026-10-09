/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "network/MbedTlsHttp.hpp"

#include "network/HttpWire.hpp"
#include "util/Files.hpp"
#include "util/Log.hpp"

#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/error.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/ssl.h>
#include <psa/crypto.h>

#include <charconv>
#include <mutex>

namespace orchard::boot {

namespace {

constexpr int kIdleTimeoutSeconds = 30;

std::string tlsError(int code) {
  char text[160];
  mbedtls_strerror(code, text, sizeof text);
  return text;
}

const mbedtls_x509_crt *trustChain(const std::vector<std::filesystem::path> &files) {
  static mbedtls_x509_crt chain;
  static bool loaded = false;
  static std::once_flag once;
  std::call_once(once, [&] {
    mbedtls_x509_crt_init(&chain);
    for (const auto &path : files) {
      std::error_code error;
      const std::string name = toUtf8(path);
      const int ret = std::filesystem::is_directory(path, error)
                          ? mbedtls_x509_crt_parse_path(&chain, name.c_str())
                          : mbedtls_x509_crt_parse_file(&chain, name.c_str());
      // A positive result counts certificates that failed to parse; the rest loaded.
      loaded = loaded || (ret >= 0 && chain.version != 0);
    }
    // TLS 1.3 runs its key exchange through PSA, which needs one global init.
    psa_crypto_init();
  });
  if (!loaded)
    throw NetworkError("no trusted CA certificates found on this system (set SSL_CERT_FILE)");
  return &chain;
}

class MbedTlsSession final : public HttpSession {
public:
  explicit MbedTlsSession(const mbedtls_x509_crt *ca) {
    mbedtls_entropy_init(&entropy_);
    mbedtls_ctr_drbg_init(&drbg_);
    mbedtls_ssl_config_init(&conf_);
    mbedtls_net_init(&net_);
    mbedtls_ssl_init(&ssl_);
    static constexpr unsigned char personal[] = "orchard-bootstrap";
    if (mbedtls_ctr_drbg_seed(&drbg_, mbedtls_entropy_func, &entropy_, personal, sizeof personal) != 0 ||
        mbedtls_ssl_config_defaults(&conf_, MBEDTLS_SSL_IS_CLIENT, MBEDTLS_SSL_TRANSPORT_STREAM,
                                    MBEDTLS_SSL_PRESET_DEFAULT) != 0)
      throw NetworkError("TLS setup failed");
    mbedtls_ssl_conf_authmode(&conf_, MBEDTLS_SSL_VERIFY_REQUIRED);
    mbedtls_ssl_conf_ca_chain(&conf_, const_cast<mbedtls_x509_crt *>(ca), nullptr);
    mbedtls_ssl_conf_rng(&conf_, mbedtls_ctr_drbg_random, &drbg_);
    // Short reads let cancellation land within a second.
    mbedtls_ssl_conf_read_timeout(&conf_, 1000);
  }

  ~MbedTlsSession() override {
    disconnect();
    mbedtls_ssl_free(&ssl_);
    mbedtls_ssl_config_free(&conf_);
    mbedtls_ctr_drbg_free(&drbg_);
    mbedtls_entropy_free(&entropy_);
  }

  void get(const std::string &url, std::uint64_t offset, HttpSink &sink, const CancelToken &cancel) override {
    std::string current = url;
    for (int hop = 0; hop < 6; ++hop) {
      const http::Url target = http::parseHttpsUrl(current);
      std::string location;
      if (exchange(target, offset, sink, cancel, location))
        return;
      current = http::resolveLocation(target, location);
    }
    throw NetworkError("too many redirects for " + url);
  }

private:
  void disconnect() {
    if (!connected_)
      return;
    mbedtls_ssl_free(&ssl_);
    mbedtls_ssl_init(&ssl_);
    mbedtls_net_free(&net_);
    mbedtls_net_init(&net_);
    connected_ = false;
  }

  void connect(const http::Url &url, const CancelToken &cancel) {
    disconnect();
    if (const int ret = mbedtls_net_connect(&net_, url.host.c_str(), url.port.c_str(), MBEDTLS_NET_PROTO_TCP))
      throw NetworkError("cannot connect to " + url.host + ": " + tlsError(ret));
    connected_ = true;
    host_ = url.host;
    port_ = url.port;
    if (mbedtls_ssl_setup(&ssl_, &conf_) != 0 || mbedtls_ssl_set_hostname(&ssl_, url.host.c_str()) != 0)
      throw NetworkError("TLS setup failed");
    mbedtls_ssl_set_bio(&ssl_, &net_, mbedtls_net_send, nullptr, mbedtls_net_recv_timeout);
    for (int idle = 0;;) {
      const int ret = mbedtls_ssl_handshake(&ssl_);
      if (ret == 0)
        break;
      if (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE)
        continue;
      if (ret == MBEDTLS_ERR_SSL_TIMEOUT && ++idle < kIdleTimeoutSeconds) {
        cancel.throwIfCancelled();
        continue;
      }
      disconnect();
      if (ret == MBEDTLS_ERR_X509_CERT_VERIFY_FAILED)
        throw NetworkError("certificate verification failed for " + url.host);
      throw NetworkError("TLS handshake with " + url.host + " failed: " + tlsError(ret));
    }
  }

  void sendAll(std::string_view data) {
    while (!data.empty()) {
      const int ret = mbedtls_ssl_write(&ssl_, reinterpret_cast<const unsigned char *>(data.data()), data.size());
      if (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE)
        continue;
      if (ret < 0)
        throw NetworkError("TLS write failed: " + tlsError(ret));
      data.remove_prefix(static_cast<std::size_t>(ret));
    }
  }

  // Returns 0 at end of stream.
  std::size_t readSome(char *buffer, std::size_t capacity, const CancelToken &cancel) {
    for (int idle = 0;;) {
      cancel.throwIfCancelled();
      const int ret = mbedtls_ssl_read(&ssl_, reinterpret_cast<unsigned char *>(buffer), capacity);
      if (ret > 0)
        return static_cast<std::size_t>(ret);
      if (ret == 0 || ret == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY)
        return 0;
      if (ret == MBEDTLS_ERR_SSL_WANT_READ || ret == MBEDTLS_ERR_SSL_WANT_WRITE ||
          ret == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET)
        continue;
      if (ret == MBEDTLS_ERR_SSL_TIMEOUT && ++idle < kIdleTimeoutSeconds)
        continue;
      throw NetworkError(ret == MBEDTLS_ERR_SSL_TIMEOUT ? "connection timed out" : "TLS read failed: " + tlsError(ret));
    }
  }

  // Returns false with `location` set when the server redirects.
  bool exchange(const http::Url &url, std::uint64_t offset, HttpSink &sink, const CancelToken &cancel,
                std::string &location) {
    const bool reused = connected_ && host_ == url.host && port_ == url.port;
    if (!reused)
      connect(url, cancel);
    std::string request = "GET " + url.target + " HTTP/1.1\r\nHost: " + url.host +
                          (url.port == "443" ? "" : ":" + url.port) + "\r\nUser-Agent: " + kUserAgent +
                          "\r\nAccept-Encoding: identity\r\nConnection: keep-alive\r\n";
    if (offset > 0)
      request += "Range: bytes=" + std::to_string(offset) + "-\r\n";
    request += "\r\n";

    std::string buffer;
    char chunk[64 * 1024];
    http::ResponseHead head;
    std::size_t headLength = 0;
    try {
      sendAll(request);
      while ((headLength = http::parseResponseHead(buffer, head)) == 0) {
        const std::size_t got = readSome(chunk, sizeof chunk, cancel);
        if (got == 0)
          throw NetworkError("connection closed before the response");
        buffer.append(chunk, got);
      }
    } catch (const NetworkError &) {
      // Servers drop idle keep-alive connections; retry once on a fresh one.
      if (!reused || !buffer.empty())
        throw;
      disconnect();
      return exchange(url, offset, sink, cancel, location);
    }

    const int status = head.status;
    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
      location = head.header("location");
      disconnect();
      if (location.empty())
        throw HttpStatusError(status, url.target);
      return false;
    }
    if (status != 200 && status != 206) {
      disconnect();
      throw HttpStatusError(status, "https://" + url.host + url.target);
    }
    std::uint64_t start = 0;
    if (status == 206) {
      const auto range = http::rangeStart(head);
      if (!range || *range != offset) {
        disconnect();
        throw NetworkError("server returned the wrong byte range");
      }
      start = *range;
    }

    const bool chunked = head.header("transfer-encoding").find("chunked") != std::string::npos;
    std::optional<std::uint64_t> length;
    if (const std::string text = head.header("content-length"); !chunked && !text.empty()) {
      std::uint64_t value = 0;
      if (std::from_chars(text.data(), text.data() + text.size(), value).ec != std::errc()) {
        disconnect();
        throw NetworkError("malformed Content-Length");
      }
      length = value;
    }
    std::string connection = head.header("connection");
    for (char &c : connection)
      c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    const bool keepAlive = connection.find("close") == std::string::npos && (chunked || length);
    try {
      readBody(sink, start, chunked, length, std::string_view(buffer).substr(headLength), cancel);
    } catch (...) {
      // Never reuse a connection left mid-body.
      disconnect();
      throw;
    }
    if (!keepAlive)
      disconnect();
    return true;
  }

  void readBody(HttpSink &sink, std::uint64_t start, bool chunked, std::optional<std::uint64_t> length, std::string_view pending,
                const CancelToken &cancel) {
    sink.onResponse(start, length);
    char chunk[64 * 1024];
    http::ChunkedDecoder decoder;
    std::string decoded;
    std::uint64_t remaining = length.value_or(0);
    while (true) {
      if (chunked) {
        decoded.clear();
        if (!decoder.feed(pending, decoded))
          throw NetworkError("malformed chunked response");
        if (!decoded.empty())
          sink.onData(decoded.data(), decoded.size());
        if (decoder.done())
          break;
      } else if (length) {
        const std::size_t take = static_cast<std::size_t>(std::min<std::uint64_t>(remaining, pending.size()));
        if (take)
          sink.onData(pending.data(), take);
        remaining -= take;
        if (remaining == 0)
          break;
      } else if (!pending.empty()) {
        sink.onData(pending.data(), pending.size());
      }
      const std::size_t got = readSome(chunk, sizeof chunk, cancel);
      if (got == 0) {
        if (chunked || length)
          throw NetworkError("connection closed mid-response");
        break;
      }
      pending = std::string_view(chunk, got);
    }
  }

  mbedtls_entropy_context entropy_;
  mbedtls_ctr_drbg_context drbg_;
  mbedtls_ssl_config conf_;
  mbedtls_net_context net_;
  mbedtls_ssl_context ssl_;
  bool connected_ = false;
  std::string host_;
  std::string port_;
};

} // namespace

std::unique_ptr<HttpSession> createMbedTlsSession(const std::vector<std::filesystem::path> &trustFiles) {
  return std::make_unique<MbedTlsSession>(trustChain(trustFiles));
}

} // namespace orchard::boot
