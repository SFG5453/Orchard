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

#include "connect/session.h"

#include <chrono>

namespace orchard::connect {
namespace {

constexpr auto kKeepaliveInterval = std::chrono::milliseconds(5000);
constexpr std::int64_t kKeepaliveTimeoutMs = 15000;

} // namespace

const char *stateName(SessionState state) {
  switch (state) {
  case SessionState::Disconnected:
    return "DISCONNECTED";
  case SessionState::Discovering:
    return "DISCOVERING";
  case SessionState::Connecting:
    return "CONNECTING";
  case SessionState::Authenticating:
    return "AUTHENTICATING";
  case SessionState::NegotiatingCapabilities:
    return "NEGOTIATING_CAPABILITIES";
  case SessionState::ResolvingInitialPlayback:
    return "RESOLVING_INITIAL_PLAYBACK";
  case SessionState::Connected:
    return "CONNECTED";
  case SessionState::Reconnecting:
    return "RECONNECTING";
  }
  return "DISCONNECTED";
}

const char *roleName(SessionRole role) { return role == SessionRole::Controller ? "controller" : "target"; }

Session::Session(std::weak_ptr<EventLoop> loop, SessionOwner &owner, Grant grant)
    : m_loop(std::move(loop)), m_owner(owner), m_grant(std::move(grant)) {}

Session::~Session() {
  if (auto loop = m_loop.lock()) {
    loop->cancel(m_handshakeTimer);
    loop->cancel(m_keepaliveTimer);
  }
  if (m_transport)
    m_transport->close();
}

const char *Session::transportKind() const { return m_transport ? m_transport->kind() : ""; }

void Session::setState(SessionState state) {
  if (m_state == state)
    return;
  m_state = state;
  m_owner.sessionEvent(*this, "state", Json::object());
}

void Session::bindTransport() {
  std::weak_ptr<Session> weak = weak_from_this();
  ConnectTransport::Callbacks callbacks;
  callbacks.text = [weak](std::string text) {
    if (auto self = weak.lock())
      self->onText(std::move(text));
  };
  callbacks.binary = [weak](std::string bytes) {
    if (auto self = weak.lock())
      self->onBinary(std::move(bytes));
  };
  callbacks.closed = [weak](std::string reason) {
    if (auto self = weak.lock())
      self->onClosed(reason);
  };
  m_transport->bind(m_loop, std::move(callbacks));
}

void Session::adoptAsController(std::shared_ptr<ConnectTransport> transport) {
  m_channel.reset();
  m_transport = std::move(transport);
  bindTransport();
  touch();
  setState(SessionState::Authenticating);
  sendHello();
  armHandshakeTimer();
}

void Session::adoptAsTarget(std::shared_ptr<ConnectTransport> transport, const Json &hello) {
  if (m_transport && m_transport != transport)
    m_transport->close();
  m_channel.reset();
  m_transport = std::move(transport);
  bindTransport();
  touch();
  setState(SessionState::Authenticating);
  const auto nonce = base64Decode(jsonString(hello, "nonce", 128));
  if (!nonce || nonce->size() != kNonceBytes) {
    sendClear({{"type", msg::Reject}, {"code", code::Unauthorized}});
    dropTransport();
    m_owner.linkLost(*this, code::Unauthorized);
    return;
  }
  m_nonceController = *nonce;
  m_nonceTarget = randomBytes(kNonceBytes);
  const DeviceInfo &self = m_owner.selfDevice();
  Json challenge = {
      {"type", msg::Challenge},
      {"session_id", id()},
      {"device_id", self.id},
      {"nonce", base64Encode(m_nonceTarget)},
      {"proof", base64Encode(handshakeProof(m_grant.key, "target", id(), m_nonceController, m_nonceTarget,
                                            peer().id, self.id))}};
  stampProtocol(challenge);
  m_handshakeSentAtMs = monotonicMs();
  sendClear(challenge);
  armHandshakeTimer();
}

void Session::onText(std::string text) {
  touch();
  // Cleartext ends at the handshake; anything later is a downgrade attempt.
  if (m_channel.ready() || text.size() > 4096) {
    dropTransport();
    m_owner.linkLost(*this, "protocol_error");
    return;
  }
  const auto message = parseJson(text);
  const std::string type = message ? jsonString(*message, "type", 32) : std::string();
  if (type == msg::Reject)
    handleReject(*message);
  else if (role() == SessionRole::Controller && type == msg::Challenge)
    handleChallenge(*message);
  else if (role() == SessionRole::Target && type == msg::Auth)
    handleAuth(*message);
}

void Session::onBinary(std::string bytes) {
  touch();
  if (!m_channel.ready()) {
    dropTransport();
    m_owner.linkLost(*this, "protocol_error");
    return;
  }
  SecureChannel::Kind kind;
  std::string plain;
  const auto result = m_channel.open(bytes, kind, plain);
  if (result == SecureChannel::OpenResult::Incomplete)
    return;
  if (result == SecureChannel::OpenResult::Failed) {
    dropTransport();
    m_owner.linkLost(*this, "decrypt_failed");
    return;
  }
  if (kind == SecureChannel::Kind::Json) {
    if (auto message = parseJson(plain))
      handleSealed(*message);
    return;
  }
  // Data: [u32 header length][header json][payload]
  if (plain.size() < 4 || m_state != SessionState::Connected)
    return;
  const std::uint32_t headerLength = (std::uint32_t(std::uint8_t(plain[0])) << 24) |
                                     (std::uint32_t(std::uint8_t(plain[1])) << 16) |
                                     (std::uint32_t(std::uint8_t(plain[2])) << 8) | std::uint8_t(plain[3]);
  if (headerLength > 16 * 1024 || 4 + headerLength > plain.size())
    return;
  auto header = parseJson(std::string_view(plain).substr(4, headerLength));
  if (!header || !header->is_object())
    return;
  m_owner.sessionData(*this, std::move(*header), plain.substr(4 + headerLength));
}

void Session::handleSealed(const Json &message) {
  const std::string type = jsonString(message, "type", 32);
  const bool connected = m_state == SessionState::Connected;
  if (type == msg::Capabilities) {
    handleCapabilities(message);
  } else if (type == msg::Roles && role() == SessionRole::Controller) {
    handleRoles(message);
  } else if (type == msg::InitialPlayback && role() == SessionRole::Controller) {
    handleInitialPlayback(message);
  } else if (type == msg::State && role() == SessionRole::Controller) {
    m_peerSnapshot = snapshotFromJson(message.value("playback", Json::object()));
    // Half a round trip has already played since the target read its clock.
    m_peerSnapshot.position = projectPosition(m_peerSnapshot, m_rttMs / 2);
    m_owner.sessionEvent(*this, "remote_state", toJson(m_peerSnapshot));
  } else if (type == msg::Command && role() == SessionRole::Target) {
    const std::string commandId = jsonString(message, "id", 64);
    Json command = message.value("command", Json::object());
    if (!connected) {
      sendCommandResult(commandId, code::NotConnected);
      return;
    }
    if (std::string error = normalizeCommand(command); !error.empty()) {
      sendCommandResult(commandId, error);
      return;
    }
    m_owner.sessionEvent(*this, "command", {{"id", commandId}, {"command", command}});
    sendCommandResult(commandId, {});
  } else if (type == msg::CommandResult && role() == SessionRole::Controller) {
    m_owner.sessionEvent(*this, "command_result",
                         {{"id", jsonString(message, "id", 64)},
                          {"ok", jsonBool(message, "ok")},
                          {"error", jsonString(message, "error", 64)}});
  } else if (type == msg::Rpc && connected) {
    m_owner.sessionEvent(*this, "rpc", message);
  } else if (type == msg::RpcResult) {
    m_owner.sessionEvent(*this, "rpc_result", message);
  } else if (type == msg::StreamEnd && connected) {
    m_owner.sessionEvent(*this, "stream_end", message);
  } else if (type == msg::Ping) {
    sendSealed({{"type", msg::Pong}, {"t", message.value("t", Json(0))}});
  } else if (type == msg::Pong) {
    const auto sentAt = static_cast<std::int64_t>(jsonNumber(message, "t", 0));
    const std::int64_t now = monotonicMs();
    if (sentAt > 0 && sentAt <= now)
      m_rttMs = now - sentAt;
  } else if (type == msg::Bye) {
    const std::string reason = jsonString(message, "reason", 64);
    finish(reason.empty() ? "peer_left" : reason);
    m_owner.sessionFinished(*this, reason.empty() ? "peer_left" : reason);
  }
}

void Session::sendState(const PlaybackSnapshot &snapshot) {
  if (role() != SessionRole::Target || !m_channel.ready())
    return;
  sendSealed({{"type", msg::State}, {"playback", toJson(snapshot)}});
  lastSent = snapshot;
  lastSentAtMs = monotonicMs();
}

void Session::sendRoles(const Roles &roles) {
  m_roles = roles;
  if (m_channel.ready())
    sendSealed({{"type", msg::Roles}, {"roles", toJson(roles)}});
}

void Session::sendCommandResult(const std::string &commandId, const std::string &error) {
  sendSealed({{"type", msg::CommandResult}, {"id", commandId}, {"ok", error.empty()}, {"error", error}});
}

bool Session::sendCommand(const std::string &commandId, const Json &command) {
  if (role() != SessionRole::Controller || m_state != SessionState::Connected)
    return false;
  return sendSealed({{"type", msg::Command}, {"id", commandId}, {"command", command}});
}

bool Session::sendRpc(const Json &request) {
  if (m_state != SessionState::Connected)
    return false;
  Json message = request;
  message["type"] = msg::Rpc;
  return sendSealed(message);
}

void Session::sendRpcResult(const Json &result) {
  Json message = result;
  message["type"] = msg::RpcResult;
  sendSealed(message);
}

bool Session::sendData(const Json &header, const std::string &payload) {
  // Never drops data; the node pauses senders through flow events instead.
  if (m_state != SessionState::Connected || !m_transport)
    return false;
  const std::string head = header.dump();
  std::string plain;
  plain.reserve(4 + head.size() + payload.size());
  const auto length = static_cast<std::uint32_t>(head.size());
  for (int shift = 24; shift >= 0; shift -= 8)
    plain.push_back(static_cast<char>((length >> shift) & 0xff));
  plain += head;
  plain += payload;
  for (const std::string &frame : m_channel.seal(SecureChannel::Kind::Data, plain)) {
    if (!m_transport->sendBinary(frame))
      return false;
  }
  return true;
}

std::size_t Session::bufferedAmount() const { return m_transport ? m_transport->bufferedAmount() : 0; }

void Session::bye(const std::string &reason) {
  if (m_transport && m_channel.ready())
    sendSealed({{"type", msg::Bye}, {"reason", reason}});
  finish(reason);
}

void Session::dropTransport() {
  if (auto loop = m_loop.lock()) {
    loop->cancel(m_handshakeTimer);
    loop->cancel(m_keepaliveTimer);
  }
  m_handshakeTimer = m_keepaliveTimer = 0;
  if (m_transport)
    m_transport->close();
  m_transport.reset();
  m_channel.reset();
}

void Session::onClosed(const std::string &reason) {
  if (m_finished)
    return;
  dropTransport();
  m_owner.linkLost(*this, reason);
}

void Session::finish(const std::string &) {
  m_finished = true;
  dropTransport();
}

bool Session::sendSealed(const Json &message) {
  if (!m_transport || !m_channel.ready())
    return false;
  for (const std::string &frame : m_channel.seal(SecureChannel::Kind::Json, message.dump())) {
    if (!m_transport->sendBinary(frame))
      return false;
  }
  return true;
}

bool Session::sendClear(const Json &message) { return m_transport && m_transport->sendText(message.dump()); }

void Session::armHandshakeTimer() {
  auto loop = m_loop.lock();
  if (!loop)
    return;
  loop->cancel(m_handshakeTimer);
  std::weak_ptr<Session> weak = weak_from_this();
  m_handshakeTimer = loop->after(std::chrono::seconds(10), [weak] {
    auto self = weak.lock();
    if (!self || self->m_state == SessionState::Connected || !self->m_transport)
      return;
    self->dropTransport();
    self->m_owner.linkLost(*self, "handshake_timeout");
  });
}

void Session::armKeepalive() {
  auto loop = m_loop.lock();
  if (!loop)
    return;
  loop->cancel(m_keepaliveTimer);
  std::weak_ptr<Session> weak = weak_from_this();
  m_keepaliveTimer = loop->after(kKeepaliveInterval, [weak] {
    auto self = weak.lock();
    if (!self || !self->m_transport)
      return;
    if (monotonicMs() - self->m_lastInboundMs > kKeepaliveTimeoutMs) {
      self->dropTransport();
      self->m_owner.linkLost(*self, "keepalive_timeout");
      return;
    }
    self->sendSealed({{"type", msg::Ping}, {"t", monotonicMs()}});
    self->armKeepalive();
  });
}

} // namespace orchard::connect
