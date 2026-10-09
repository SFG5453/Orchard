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

#include "connect/secure_channel.h"

#include <mbedtls/chachapoly.h>
#include <mbedtls/hkdf.h>
#include <mbedtls/md.h>

#include <algorithm>
#include <array>

namespace orchard::connect {
namespace {

constexpr std::size_t kTagBytes = 16;
constexpr std::size_t kHeaderBytes = 2;
constexpr std::uint8_t kFinal = 1;

const unsigned char *bytes(std::string_view text) { return reinterpret_cast<const unsigned char *>(text.data()); }

void appendField(std::string &out, std::string_view field) {
  const auto size = static_cast<std::uint32_t>(field.size());
  for (int shift = 24; shift >= 0; shift -= 8)
    out.push_back(static_cast<char>((size >> shift) & 0xff));
  out.append(field);
}

std::array<unsigned char, 12> nonceFor(std::uint64_t counter) {
  std::array<unsigned char, 12> nonce{};
  for (int i = 0; i < 8; ++i)
    nonce[4 + i] = static_cast<unsigned char>((counter >> (8 * i)) & 0xff);
  return nonce;
}

} // namespace

std::string handshakeProof(std::string_view sessionKey, std::string_view label, std::string_view sessionId,
                           std::string_view nonceController, std::string_view nonceTarget,
                           std::string_view controllerId, std::string_view targetId) {
  std::string transcript;
  for (std::string_view field : {label, sessionId, nonceController, nonceTarget, controllerId, targetId})
    appendField(transcript, field);
  std::string mac(32, '\0');
  mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), bytes(sessionKey), sessionKey.size(),
                  bytes(transcript), transcript.size(), reinterpret_cast<unsigned char *>(mac.data()));
  return mac;
}

bool equalSecret(std::string_view left, std::string_view right) {
  if (left.size() != right.size())
    return false;
  unsigned char diff = 0;
  for (std::size_t i = 0; i < left.size(); ++i)
    diff |= static_cast<unsigned char>(left[i] ^ right[i]);
  return diff == 0;
}

struct SecureChannel::Cipher {
  mbedtls_chachapoly_context context;
  Cipher() { mbedtls_chachapoly_init(&context); }
  ~Cipher() { mbedtls_chachapoly_free(&context); }
};

SecureChannel::SecureChannel() = default;
SecureChannel::~SecureChannel() = default;

bool SecureChannel::establish(std::string_view sessionKey, std::string_view nonceController,
                              std::string_view nonceTarget, bool isController) {
  const std::string salt = std::string(nonceController) + std::string(nonceTarget);
  std::array<unsigned char, 32> controllerToTarget{};
  std::array<unsigned char, 32> targetToController{};
  const auto *sha256 = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  static const char c2t[] = "orchard-connect-v2 controller->target";
  static const char t2c[] = "orchard-connect-v2 target->controller";
  if (mbedtls_hkdf(sha256, bytes(salt), salt.size(), bytes(sessionKey), sessionKey.size(),
                   reinterpret_cast<const unsigned char *>(c2t), sizeof c2t - 1, controllerToTarget.data(), 32) != 0 ||
      mbedtls_hkdf(sha256, bytes(salt), salt.size(), bytes(sessionKey), sessionKey.size(),
                   reinterpret_cast<const unsigned char *>(t2c), sizeof t2c - 1, targetToController.data(), 32) != 0)
    return false;
  m_send = std::make_unique<Cipher>();
  m_receive = std::make_unique<Cipher>();
  const auto &sendKey = isController ? controllerToTarget : targetToController;
  const auto &receiveKey = isController ? targetToController : controllerToTarget;
  if (mbedtls_chachapoly_setkey(&m_send->context, sendKey.data()) != 0 ||
      mbedtls_chachapoly_setkey(&m_receive->context, receiveKey.data()) != 0)
    return false;
  m_sendCounter = 0;
  m_receiveCounter = 0;
  m_partial.clear();
  m_ready = true;
  m_failed = false;
  return true;
}

void SecureChannel::reset() {
  m_send.reset();
  m_receive.reset();
  m_sendCounter = 0;
  m_receiveCounter = 0;
  m_partial.clear();
  m_ready = false;
  m_failed = false;
}

std::vector<std::string> SecureChannel::seal(Kind kind, std::string_view plaintext) {
  std::vector<std::string> frames;
  if (!m_ready || m_failed)
    return frames;
  std::size_t offset = 0;
  do {
    const std::size_t chunk = std::min(kFragmentBytes, plaintext.size() - offset);
    const bool final = offset + chunk == plaintext.size();
    std::string frame(kHeaderBytes + chunk + kTagBytes, '\0');
    frame[0] = static_cast<char>(kind);
    frame[1] = static_cast<char>(final ? kFinal : 0);
    auto *out = reinterpret_cast<unsigned char *>(frame.data());
    const auto nonce = nonceFor(m_sendCounter++);
    mbedtls_chachapoly_encrypt_and_tag(&m_send->context, chunk, nonce.data(), out, kHeaderBytes,
                                       bytes(plaintext.substr(offset, chunk)), out + kHeaderBytes,
                                       out + kHeaderBytes + chunk);
    frames.push_back(std::move(frame));
    offset += chunk;
  } while (offset < plaintext.size());
  return frames;
}

SecureChannel::OpenResult SecureChannel::open(std::string_view frame, Kind &kind, std::string &message) {
  if (!m_ready || m_failed || frame.size() < kHeaderBytes + kTagBytes)
    return OpenResult::Failed;
  const auto rawKind = static_cast<std::uint8_t>(frame[0]);
  const bool final = (static_cast<std::uint8_t>(frame[1]) & kFinal) != 0;
  const std::size_t length = frame.size() - kHeaderBytes - kTagBytes;
  std::string plain(length, '\0');
  const auto nonce = nonceFor(m_receiveCounter++);
  const auto *in = bytes(frame);
  if (mbedtls_chachapoly_auth_decrypt(&m_receive->context, length, nonce.data(), in, kHeaderBytes,
                                      in + kHeaderBytes + length, in + kHeaderBytes,
                                      reinterpret_cast<unsigned char *>(plain.data())) != 0 ||
      (rawKind != static_cast<std::uint8_t>(Kind::Json) && rawKind != static_cast<std::uint8_t>(Kind::Data)) ||
      (!m_partial.empty() && rawKind != m_partialKind)) {
    // One forged or reordered frame poisons the stream for good.
    m_failed = true;
    return OpenResult::Failed;
  }
  if (m_partial.size() + plain.size() > kMaxMessageBytes) {
    m_failed = true;
    return OpenResult::Failed;
  }
  m_partial += plain;
  m_partialKind = rawKind;
  if (!final)
    return OpenResult::Incomplete;
  kind = static_cast<Kind>(rawKind);
  message.swap(m_partial);
  m_partial.clear();
  return OpenResult::Message;
}

} // namespace orchard::connect
