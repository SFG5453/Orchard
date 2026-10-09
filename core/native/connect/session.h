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

#include "connect/device.h"
#include "connect/event_loop.h"
#include "connect/playback.h"
#include "connect/secure_channel.h"
#include "connect/transport.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace orchard::connect {

enum class SessionState {
  Disconnected,
  Discovering,
  Connecting,
  Authenticating,
  NegotiatingCapabilities,
  ResolvingInitialPlayback,
  Connected,
  Reconnecting,
};
const char *stateName(SessionState state);

enum class SessionRole { Controller, Target };
const char *roleName(SessionRole role);

// Issued by the account hub to both ends of one relationship.
struct Grant {
  std::string sessionId;
  std::string key; // raw kSessionKeyBytes
  SessionRole role = SessionRole::Controller;
  DeviceInfo peer;
  std::vector<LanEndpoint> peerLan;
  Json iceServers = Json::array();
  std::int64_t establishDeadlineMs = 0; // monotonic; only gates the first handshake
};

class Session;

// Node-side hooks. All calls happen on the Connect loop.
class SessionOwner {
public:
  virtual ~SessionOwner() = default;
  [[nodiscard]] virtual const DeviceInfo &selfDevice() const = 0;
  // The local playback state with its position projected to now.
  [[nodiscard]] virtual PlaybackSnapshot localSnapshot() const = 0;
  virtual Roles rolesFor(Session &session) = 0;
  // A named session event; the node turns these into host events.
  virtual void sessionEvent(Session &session, const std::string &name, Json payload) = 0;
  virtual void sessionData(Session &session, Json header, std::string payload) = 0;
  // The transport dropped. The owner reconnects or ends the session.
  virtual void linkLost(Session &session, const std::string &reason) = 0;
  // The peer ended the session or broke the protocol; no reconnect.
  virtual void sessionFinished(Session &session, const std::string &reason) = 0;
};

class Session : public std::enable_shared_from_this<Session> {
public:
  Session(std::weak_ptr<EventLoop> loop, SessionOwner &owner, Grant grant);
  ~Session();

  [[nodiscard]] const std::string &id() const { return m_grant.sessionId; }
  [[nodiscard]] SessionRole role() const { return m_grant.role; }
  [[nodiscard]] SessionState state() const { return m_state; }
  [[nodiscard]] const DeviceInfo &peer() const { return m_grant.peer; }
  [[nodiscard]] const Grant &grant() const { return m_grant; }
  Grant &grant() { return m_grant; }
  [[nodiscard]] bool resolved() const { return m_resolved; }
  [[nodiscard]] const char *transportKind() const;
  [[nodiscard]] bool hasTransport() const { return static_cast<bool>(m_transport); }
  [[nodiscard]] std::int64_t rttMs() const { return m_rttMs; }
  [[nodiscard]] const Roles &roles() const { return m_roles; }

  void setState(SessionState state);

  // Controller: an opened transport; sends hello.
  void adoptAsController(std::shared_ptr<ConnectTransport> transport);
  // Target: a transport whose first message was this hello.
  void adoptAsTarget(std::shared_ptr<ConnectTransport> transport, const Json &hello);

  // Target to controller.
  void sendState(const PlaybackSnapshot &snapshot);
  void sendRoles(const Roles &roles);
  // Mid-session capability change, e.g. a provider sign-in.
  void sendDeviceUpdate();
  void sendCommandResult(const std::string &id, const std::string &error);
  // Controller to target.
  bool sendCommand(const std::string &id, const Json &command);
  // Either direction.
  bool sendRpc(const Json &request);
  void sendRpcResult(const Json &result);
  bool sendData(const Json &header, const std::string &payload);
  [[nodiscard]] std::size_t bufferedAmount() const;

  // Graceful end: tells the peer, closes the transport. The session is finished.
  void bye(const std::string &reason);
  // Closes the transport without ending the session, for reconnects.
  void dropTransport();

  // Last state the target sent, for change detection.
  PlaybackSnapshot lastSent;
  std::int64_t lastSentAtMs = 0;
  // Bumped per dial so stale transports from an older attempt are ignored.
  std::uint64_t attempt = 0;
  std::int64_t reconnectDeadlineMs = 0;
  int reconnectTries = 0;
  std::uint64_t webrtcAttempt = 0;

private:
  void bindTransport();
  void onText(std::string text);
  void onBinary(std::string bytes);
  void onClosed(const std::string &reason);
  void finish(const std::string &reason);

  // session_handshake.cpp
  void sendHello();
  void handleChallenge(const Json &message);
  void handleAuth(const Json &message);
  void handleReject(const Json &message);
  void sendCapabilities();
  void handleCapabilities(const Json &message);
  void handleRoles(const Json &message);
  void handleInitialPlayback(const Json &message);
  void becomeConnected();

  void handleSealed(const Json &message);
  bool sendSealed(const Json &message);
  bool sendClear(const Json &message);
  void armHandshakeTimer();
  void armKeepalive();
  void touch() { m_lastInboundMs = monotonicMs(); }

  std::weak_ptr<EventLoop> m_loop;
  SessionOwner &m_owner;
  Grant m_grant;
  SessionState m_state = SessionState::Discovering;
  std::shared_ptr<ConnectTransport> m_transport;
  SecureChannel m_channel;
  std::string m_nonceController;
  std::string m_nonceTarget;
  bool m_resolved = false;
  bool m_finished = false;
  bool m_haveCapabilities = false;
  PlaybackSnapshot m_peerSnapshot;
  std::int64_t m_peerSnapshotAtMs = 0;
  Roles m_roles;
  std::int64_t m_rttMs = 0;
  // Hello (controller) or challenge (target) send time; the reply gives a first RTT.
  std::int64_t m_handshakeSentAtMs = 0;
  std::int64_t m_lastInboundMs = 0;
  EventLoop::TimerId m_handshakeTimer = 0;
  EventLoop::TimerId m_keepaliveTimer = 0;
};

} // namespace orchard::connect
