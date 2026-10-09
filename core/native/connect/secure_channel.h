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

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace orchard::connect {

constexpr std::size_t kSessionKeyBytes = 32;
constexpr std::size_t kNonceBytes = 32;

// HMAC-SHA256 over a length-prefixed transcript, binding the proof to both
// nonces and both device ids so it cannot be replayed into another session.
std::string handshakeProof(std::string_view sessionKey, std::string_view label, std::string_view sessionId,
                           std::string_view nonceController, std::string_view nonceTarget,
                           std::string_view controllerId, std::string_view targetId);
bool equalSecret(std::string_view left, std::string_view right);

// ChaCha20-Poly1305 framing applied on top of every transport, LAN or WebRTC.
// Keys are per direction and nonces are implicit counters, which the ordered,
// reliable transports make safe. Large messages are split into fragments.
class SecureChannel {
public:
  enum class Kind : std::uint8_t { Json = 1, Data = 2 };
  enum class OpenResult { Incomplete, Message, Failed };

  // Plaintext bytes per frame; well under every transport's message limit.
  static constexpr std::size_t kFragmentBytes = 60 * 1024;
  static constexpr std::size_t kMaxMessageBytes = 16 * 1024 * 1024;

  SecureChannel();
  ~SecureChannel();
  SecureChannel(const SecureChannel &) = delete;
  SecureChannel &operator=(const SecureChannel &) = delete;

  bool establish(std::string_view sessionKey, std::string_view nonceController, std::string_view nonceTarget,
                 bool isController);
  [[nodiscard]] bool ready() const { return m_ready; }
  // Forgets keys and counters; a new transport needs a new handshake.
  void reset();

  std::vector<std::string> seal(Kind kind, std::string_view plaintext);
  OpenResult open(std::string_view frame, Kind &kind, std::string &message);

private:
  struct Cipher;
  std::unique_ptr<Cipher> m_send;
  std::unique_ptr<Cipher> m_receive;
  std::uint64_t m_sendCounter = 0;
  std::uint64_t m_receiveCounter = 0;
  bool m_ready = false;
  bool m_failed = false;
  std::string m_partial;
  std::uint8_t m_partialKind = 0;
};

} // namespace orchard::connect
