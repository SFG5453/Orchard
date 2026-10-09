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

#include "connect/lan_transport.h"

#include "connect/protocol.h"

#include <rtc/rtc.hpp>

#include <chrono>
#include <variant>

namespace orchard::connect {
namespace {

// Pre-auth peers get little room; sealed fragments stay under this too.
constexpr std::size_t kMaxLanMessage = 256 * 1024;

rtc::WebSocket::Configuration clientConfig() {
  rtc::WebSocket::Configuration config;
  config.connectionTimeout = std::chrono::milliseconds(2500);
  config.pingInterval = std::chrono::milliseconds(5000);
  config.maxOutstandingPings = 3;
  config.maxMessageSize = kMaxLanMessage;
  return config;
}

} // namespace

LocalConnectTransport::LocalConnectTransport() : m_socket(std::make_shared<rtc::WebSocket>(clientConfig())) {}

LocalConnectTransport::LocalConnectTransport(std::shared_ptr<rtc::WebSocket> accepted)
    : m_socket(std::move(accepted)) {}

LocalConnectTransport::~LocalConnectTransport() {
  if (m_socket) {
    m_socket->resetCallbacks();
    m_socket->close();
  }
}

void LocalConnectTransport::wire() {
  std::weak_ptr<LocalConnectTransport> weak = weak_from_this();
  m_socket->onOpen([weak] {
    if (auto self = weak.lock())
      self->emitOpened();
  });
  m_socket->onMessage([weak](rtc::message_variant data) {
    auto self = weak.lock();
    if (!self)
      return;
    if (auto *text = std::get_if<std::string>(&data))
      self->emitText(std::move(*text));
    else if (auto *binary = std::get_if<rtc::binary>(&data))
      self->emitBinary(std::string(reinterpret_cast<const char *>(binary->data()), binary->size()));
  });
  m_socket->onError([weak](std::string error) {
    if (auto self = weak.lock(); self && !self->m_closed.exchange(true))
      self->emitClosed("lan_error: " + error);
  });
  m_socket->onClosed([weak] {
    if (auto self = weak.lock(); self && !self->m_closed.exchange(true))
      self->emitClosed("lan_closed");
  });
}

void LocalConnectTransport::open(const std::string &host, std::uint16_t port) {
  wire();
  try {
    m_socket->open("ws://" + host + ":" + std::to_string(port) + kLanPath);
  } catch (const std::exception &error) {
    if (!m_closed.exchange(true))
      emitClosed(std::string("lan_open: ") + error.what());
  }
}

void LocalConnectTransport::attach() {
  wire();
  const auto path = m_socket->path();
  if (path && *path != kLanPath) {
    close();
    return;
  }
  // The server hands sockets over before or after the HTTP upgrade completes.
  if (m_socket->isOpen())
    emitOpened();
  else if (m_socket->isClosed() && !m_closed.exchange(true))
    emitClosed("lan_closed");
}

bool LocalConnectTransport::isOpen() const { return m_socket && m_socket->isOpen(); }

bool LocalConnectTransport::sendText(const std::string &text) {
  try {
    if (!isOpen())
      return false;
    // false only means "buffered", which an ordered stream is fine with.
    m_socket->send(text);
    return true;
  } catch (const std::exception &) {
    return false;
  }
}

bool LocalConnectTransport::sendBinary(const std::string &bytes) {
  try {
    if (!isOpen())
      return false;
    m_socket->send(reinterpret_cast<const std::byte *>(bytes.data()), bytes.size());
    return true;
  } catch (const std::exception &) {
    return false;
  }
}

std::size_t LocalConnectTransport::bufferedAmount() const { return m_socket ? m_socket->bufferedAmount() : 0; }

void LocalConnectTransport::close() {
  unbind();
  if (m_socket)
    m_socket->close();
}

LanServer::LanServer() = default;

LanServer::~LanServer() { stop(); }

bool LanServer::start(std::uint16_t preferredPort, Accept accept) {
  stop();
  for (std::uint16_t port : {preferredPort, std::uint16_t(0)}) {
    rtc::WebSocketServer::Configuration config;
    config.port = port;
    config.bindAddress = "0.0.0.0";
    config.connectionTimeout = std::chrono::milliseconds(5000);
    config.maxMessageSize = kMaxLanMessage;
    try {
      m_server = std::make_unique<rtc::WebSocketServer>(config);
    } catch (const std::exception &) {
      m_server.reset();
      continue;
    }
    m_port = m_server->port();
    m_server->onClient([accept](std::shared_ptr<rtc::WebSocket> socket) {
      accept(std::make_shared<LocalConnectTransport>(std::move(socket)));
    });
    return true;
  }
  return false;
}

void LanServer::stop() {
  if (m_server)
    m_server->stop();
  m_server.reset();
  m_port = 0;
}

} // namespace orchard::connect
