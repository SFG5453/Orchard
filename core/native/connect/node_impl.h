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
#include "connect/lan_transport.h"
#include "connect/node.h"
#include "connect/playback.h"
#include "connect/session.h"
#include "connect/stream.h"
#include "connect/webrtc_transport.h"

#include <deque>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

namespace orchard::connect {

// A device the hub reports online.
struct Presence {
  DeviceInfo device;
  std::vector<LanEndpoint> lan;
  bool compatible = false;
};

struct PendingRpc {
  std::string sessionId;
  EventLoop::TimerId timer = 0;
};

// Lives on the Connect loop only. node.cpp: API, events, roles, playback. node_rpc.cpp: commands
// and RPCs. node_streams.cpp: binary data. node_hub.cpp: hub protocol. node_links.cpp: dialing.
struct Node::Impl : SessionOwner, std::enable_shared_from_this<Node::Impl> {
  Impl(NodeHost &host, NodeConfig config, std::weak_ptr<EventLoop> loop);
  ~Impl() override;

  // SessionOwner
  const DeviceInfo &selfDevice() const override { return self; }
  PlaybackSnapshot localSnapshot() const override;
  Roles rolesFor(Session &session) override;
  void sessionEvent(Session &session, const std::string &name, Json payload) override;
  void sessionData(Session &session, Json header, std::string payload) override;
  void linkLost(Session &session, const std::string &reason) override;
  void sessionFinished(Session &session, const std::string &reason) override;

  // node.cpp
  void emit(Json event);
  void emitSession(Session &session);
  void endSession(const std::string &sessionId, const std::string &reason, bool notifyPeer);
  void publish(PlaybackSnapshot snapshot);
  void broadcastState(bool force);
  void armHeartbeat();
  void updateRoles();
  Roles effectiveRoles() const;
  std::shared_ptr<Session> controllerSession() const;
  std::vector<std::shared_ptr<Session>> targetSessions(bool connectedOnly) const;
  void shutdown();
  // node_rpc.cpp
  std::string resolveHost(const std::string &host) const;
  void sendCommand(const std::string &id, Json command);
  void sendRequest(const std::string &id, Json request);
  void respond(Json response);
  void failPendingRpcs(const std::string &sessionId, const std::string &error);
  // node_streams.cpp
  void sendData(const std::string &sessionId, Json header, std::string payload);
  void watchFlow(const std::string &sessionId);
  void sendStream(OutgoingStream stream);
  void drainStreams(const std::string &sessionId);
  void failStreams(const std::string &sessionId, const std::string &error);


  // node_hub.cpp
  void hubOpened();
  void hubMessage(const std::string &text);
  void hubClosed();
  void hubSend(Json message);
  void sendPresence(bool initial);
  void schedulePresenceUpdate();
  void handlePresence(const Json &message);
  void handleGrant(const Json &message);
  void handleSignal(const Json &message);
  void handleHubError(const Json &message);
  void connectTo(const std::string &deviceId);
  std::vector<LanEndpoint> lanEndpoints() const;

  // node_links.cpp
  void startLan();
  void dial(const std::shared_ptr<Session> &session);
  void startWebRtc(const std::shared_ptr<Session> &session, std::uint64_t attempt);
  void dialOpened(const std::shared_ptr<Session> &session, std::uint64_t attempt,
                  const std::shared_ptr<ConnectTransport> &transport);
  void attemptFailed(const std::shared_ptr<Session> &session, std::uint64_t attempt, const std::string &reason);
  void scheduleReconnect(const std::shared_ptr<Session> &session, const std::string &reason);
  void acceptLan(std::shared_ptr<LocalConnectTransport> transport);
  void pendingText(const std::shared_ptr<ConnectTransport> &transport, const std::string &text);
  void dropPending(const std::shared_ptr<ConnectTransport> &transport);
  void acceptOffer(const std::string &sessionId, const Json &data);
  void armTargetGrace(const std::shared_ptr<Session> &session);

  NodeHost &host;
  NodeConfig config;
  std::weak_ptr<EventLoop> loop;
  DeviceInfo self;
  std::vector<NetworkInterface> interfaces;
  bool started = false;
  bool hubConnected = false;
  bool hubWelcomed = false;

  LanServer lanServer;
  std::map<std::string, Presence> presence;
  // Target-side grants waiting for (or backing) a controller's hello.
  std::map<std::string, Grant> grants;
  std::map<std::string, std::shared_ptr<Session>> sessions;
  // Requests this device made, keyed by request_id, for matching the hub's grant.
  std::map<std::string, std::string> requestedPeers;
  // Unauthenticated transports: LAN sockets before hello, WebRTC answers before hello.
  std::map<std::shared_ptr<ConnectTransport>, EventLoop::TimerId> pending;
  // In-flight WebRTC transports by session id, for routing hub signals.
  std::map<std::string, std::shared_ptr<WebRtcConnectTransport>> webrtc;
  // Controller dials racing for one session.
  std::map<std::string, std::vector<std::shared_ptr<ConnectTransport>>> dials;
  std::map<std::string, PendingRpc> rpcs;
  // Incoming request id -> session that must receive the answer.
  std::map<std::string, std::string> incomingRpcs;
  std::set<std::string> flowPaused;
  // AudioChunk streams waiting for link capacity, and partial ones arriving, per session.
  std::map<std::string, std::deque<OutgoingStream>> outbox;
  std::set<std::string> draining;
  std::map<std::string, StreamAssembler> inbound;
  std::map<std::string, EventLoop::TimerId> requestTimers;

  PlaybackSnapshot local;
  Json lastPresenceTrack;
  EventLoop::TimerId heartbeatTimer = 0;
  EventLoop::TimerId presenceTimer = 0;
  Roles lastRoles;
};

} // namespace orchard::connect
