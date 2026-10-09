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

#include "connect/transport.h"

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace rtc {
class WebSocket;
class WebSocketServer;
} // namespace rtc

namespace orchard::connect {

// Plain ws:// on the local network. Peers are untrusted until the session
// handshake proves the account-issued key; frames are then sealed end to end.
class LocalConnectTransport final : public ConnectTransport,
                                    public std::enable_shared_from_this<LocalConnectTransport> {
public:
  LocalConnectTransport();
  explicit LocalConnectTransport(std::shared_ptr<rtc::WebSocket> accepted);
  ~LocalConnectTransport() override;

  // Client side. bind() first so no event is lost.
  void open(const std::string &host, std::uint16_t port);
  // Server side: starts delivering events for an accepted socket.
  void attach();

  [[nodiscard]] const char *kind() const override { return "lan"; }
  [[nodiscard]] bool isOpen() const override;
  bool sendText(const std::string &text) override;
  bool sendBinary(const std::string &bytes) override;
  [[nodiscard]] std::size_t bufferedAmount() const override;
  void close() override;

private:
  void wire();

  std::shared_ptr<rtc::WebSocket> m_socket;
  std::atomic<bool> m_closed{false};
};

class LanServer {
public:
  // Runs on a library thread; must only bind the transport and post.
  using Accept = std::function<void(std::shared_ptr<LocalConnectTransport>)>;

  LanServer();
  ~LanServer();

  // Tries the preferred port, then lets the OS choose one.
  bool start(std::uint16_t preferredPort, Accept accept);
  void stop();
  [[nodiscard]] std::uint16_t port() const { return m_port; }

private:
  std::unique_ptr<rtc::WebSocketServer> m_server;
  std::uint16_t m_port = 0;
};

} // namespace orchard::connect
