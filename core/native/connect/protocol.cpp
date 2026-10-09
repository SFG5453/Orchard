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

#include "connect/protocol.h"

#include <mbedtls/base64.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <mutex>
#include <stdexcept>

namespace orchard::connect {
namespace {

class Drbg {
public:
  Drbg() {
    mbedtls_entropy_init(&m_entropy);
    mbedtls_ctr_drbg_init(&m_drbg);
    static const char personal[] = "orchard-connect";
    if (mbedtls_ctr_drbg_seed(&m_drbg, mbedtls_entropy_func, &m_entropy,
                              reinterpret_cast<const unsigned char *>(personal), sizeof personal - 1) != 0)
      throw std::runtime_error("Connect random generator could not be seeded");
  }
  ~Drbg() {
    mbedtls_ctr_drbg_free(&m_drbg);
    mbedtls_entropy_free(&m_entropy);
  }
  std::string bytes(std::size_t count) {
    std::string out(count, '\0');
    std::lock_guard lock(m_mutex);
    // The DRBG caps one request at 1 KiB.
    for (std::size_t offset = 0; offset < count;) {
      const std::size_t chunk = std::min<std::size_t>(count - offset, MBEDTLS_CTR_DRBG_MAX_REQUEST);
      if (mbedtls_ctr_drbg_random(&m_drbg, reinterpret_cast<unsigned char *>(out.data() + offset), chunk) != 0)
        throw std::runtime_error("Connect random generator failed");
      offset += chunk;
    }
    return out;
  }

private:
  std::mutex m_mutex;
  mbedtls_entropy_context m_entropy;
  mbedtls_ctr_drbg_context m_drbg;
};

Drbg &drbg() {
  static Drbg instance;
  return instance;
}

} // namespace

void stampProtocol(Json &message) {
  message["connect_protocol_major"] = kProtocolMajor;
  message["connect_protocol_minor"] = kProtocolMinor;
}

std::string checkProtocol(const Json &message) {
  // A missing major is how every pre-v2 client looks.
  if (!message.is_object() || !message.contains("connect_protocol_major") ||
      !message["connect_protocol_major"].is_number_integer())
    return code::IncompatibleClient;
  if (message["connect_protocol_major"].get<int>() != kProtocolMajor)
    return code::IncompatibleProtocol;
  return {};
}

std::optional<Json> parseJson(std::string_view text) {
  Json value = Json::parse(text.begin(), text.end(), nullptr, false);
  if (value.is_discarded())
    return std::nullopt;
  return value;
}

std::string base64Encode(std::string_view bytes) {
  std::size_t length = 0;
  mbedtls_base64_encode(nullptr, 0, &length, reinterpret_cast<const unsigned char *>(bytes.data()), bytes.size());
  std::string out(length, '\0');
  if (mbedtls_base64_encode(reinterpret_cast<unsigned char *>(out.data()), out.size(), &length,
                            reinterpret_cast<const unsigned char *>(bytes.data()), bytes.size()) != 0)
    return {};
  out.resize(length);
  return out;
}

std::optional<std::string> base64Decode(std::string_view text) {
  // Accept the url-safe alphabet too; the account service emits it.
  std::string normal(text);
  for (char &c : normal) {
    if (c == '-')
      c = '+';
    else if (c == '_')
      c = '/';
  }
  while (normal.size() % 4 != 0)
    normal.push_back('=');
  std::size_t length = 0;
  std::string out(normal.size(), '\0');
  if (mbedtls_base64_decode(reinterpret_cast<unsigned char *>(out.data()), out.size(), &length,
                            reinterpret_cast<const unsigned char *>(normal.data()), normal.size()) != 0)
    return std::nullopt;
  out.resize(length);
  return out;
}

std::string randomBytes(std::size_t count) { return drbg().bytes(count); }

std::string newId() {
  static const char digits[] = "0123456789abcdef";
  const std::string raw = randomBytes(16);
  std::string out;
  out.reserve(32);
  for (unsigned char byte : raw) {
    out.push_back(digits[byte >> 4]);
    out.push_back(digits[byte & 0x0f]);
  }
  return out;
}

std::int64_t monotonicMs() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

std::string jsonString(const Json &object, const char *key, std::size_t maxLength) {
  if (!object.is_object())
    return {};
  const auto it = object.find(key);
  if (it == object.end() || !it->is_string())
    return {};
  std::string value = it->get<std::string>();
  if (value.size() > maxLength)
    value.resize(maxLength);
  return value;
}

double jsonNumber(const Json &object, const char *key, double fallback) {
  if (!object.is_object())
    return fallback;
  const auto it = object.find(key);
  if (it == object.end() || !it->is_number())
    return fallback;
  const double value = it->get<double>();
  return std::isfinite(value) ? value : fallback;
}

bool jsonBool(const Json &object, const char *key, bool fallback) {
  if (!object.is_object())
    return fallback;
  const auto it = object.find(key);
  return it != object.end() && it->is_boolean() ? it->get<bool>() : fallback;
}

} // namespace orchard::connect
