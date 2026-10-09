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

#include "connect/node_impl.h"

#include <chrono>

namespace orchard::connect {
namespace {

constexpr auto kHeartbeat = std::chrono::milliseconds(10000);

} // namespace

// Public API: hop onto the Connect loop.

Node::Node(NodeHost &host, NodeConfig config) : m_loop(std::make_shared<EventLoop>()) {
  if (config.lanPort == 0)
    config.lanPort = kPreferredLanPort;
  m_impl = std::make_shared<Impl>(host, config, m_loop);
}

Node::~Node() {
  std::weak_ptr<Impl> weak = m_impl;
  m_loop->post([weak] {
    if (auto impl = weak.lock())
      impl->shutdown();
  });
  m_loop->stop();
  m_impl.reset();
}

template <typename Fn> void Node::post(Fn &&fn) {
  std::weak_ptr<Impl> weak = m_impl;
  m_loop->post([weak, fn = std::forward<Fn>(fn)]() mutable {
    if (auto impl = weak.lock())
      fn(*impl);
  });
}

void Node::hubOpened() { post([=](Impl &impl) { impl.hubOpened(); }); }
void Node::hubMessage(const std::string &text) { post([=](Impl &impl) { impl.hubMessage(text); }); }
void Node::hubClosed() { post([=](Impl &impl) { impl.hubClosed(); }); }

void Node::setDevice(const std::string &deviceJson) {
  post([=](Impl &impl) {
    const auto parsed = parseJson(deviceJson);
    DeviceInfo device = deviceFromJson(parsed ? *parsed : Json::object());
    // Whatever the host says, this build speaks exactly this protocol.
    device.protocolMajor = kProtocolMajor;
    device.protocolMinor = kProtocolMinor;
    impl.self = std::move(device);
    if (!impl.started) {
      impl.started = true;
      impl.startLan();
    }
    for (auto &[id, session] : impl.sessions)
      session->sendDeviceUpdate();
    impl.schedulePresenceUpdate();
    impl.updateRoles();
  });
}

void Node::setInterfaces(const std::string &interfacesJson) {
  post([=](Impl &impl) {
    std::vector<NetworkInterface> list;
    if (const auto parsed = parseJson(interfacesJson); parsed && parsed->is_array()) {
      for (const Json &item : *parsed)
        list.push_back({jsonString(item, "name", 128), jsonString(item, "address", 64)});
    }
    impl.interfaces = std::move(list);
    impl.schedulePresenceUpdate();
  });
}

void Node::publishPlayback(const std::string &snapshotJson) {
  post([=](Impl &impl) {
    const auto parsed = parseJson(snapshotJson);
    impl.publish(snapshotFromJson(parsed ? *parsed : Json::object(), kCommandQueueLimit));
  });
}

void Node::connectTo(const std::string &deviceId) { post([=](Impl &impl) { impl.connectTo(deviceId); }); }

void Node::disconnect(const std::string &sessionId) {
  post([=](Impl &impl) {
    std::vector<std::string> ids;
    for (const auto &[id, session] : impl.sessions) {
      if (sessionId.empty() || id == sessionId)
        ids.push_back(id);
    }
    for (const std::string &id : ids)
      impl.endSession(id, "user_disconnected", true);
  });
}

std::string Node::command(const std::string &commandJson) {
  const std::string id = newId();
  post([=](Impl &impl) {
    const auto parsed = parseJson(commandJson);
    impl.sendCommand(id, parsed ? *parsed : Json());
  });
  return id;
}

std::string Node::request(const std::string &requestJson) {
  const std::string id = newId();
  post([=](Impl &impl) {
    const auto parsed = parseJson(requestJson);
    impl.sendRequest(id, parsed ? *parsed : Json());
  });
  return id;
}

void Node::respond(const std::string &responseJson) {
  post([=](Impl &impl) {
    if (const auto parsed = parseJson(responseJson))
      impl.respond(*parsed);
  });
}

bool Node::sendData(const std::string &sessionId, const std::string &headerJson, const std::string &payload) {
  const auto header = parseJson(headerJson);
  if (!header || !header->is_object())
    return false;
  post([=](Impl &impl) { impl.sendData(sessionId, *header, payload); });
  return true;
}

std::string Node::sendStream(const std::string &sessionId, const std::string &metaJson, std::string payload) {
  auto meta = parseJson(metaJson);
  if (!meta || !normalizeStream(*meta, payload.size()).empty())
    return {};
  OutgoingStream stream{sessionId, *meta, std::move(payload)};
  std::string id = jsonString(stream.meta, "stream", 64);
  post([stream = std::move(stream)](Impl &impl) mutable { impl.sendStream(std::move(stream)); });
  return id;
}

void Node::dropLinks() {
  post([](Impl &impl) {
    std::vector<std::shared_ptr<Session>> live;
    for (const auto &[id, session] : impl.sessions) {
      if (session->hasTransport())
        live.push_back(session);
    }
    for (const auto &session : live) {
      session->dropTransport();
      impl.linkLost(*session, "dropped");
    }
  });
}

// Loop side.

Node::Impl::Impl(NodeHost &hostRef, NodeConfig configValue, std::weak_ptr<EventLoop> loopRef)
    : host(hostRef), config(configValue), loop(std::move(loopRef)) {
  local.timestamp = monotonicMs();
}

Node::Impl::~Impl() = default;

void Node::Impl::shutdown() {
  std::vector<std::string> ids;
  for (const auto &[id, session] : sessions)
    ids.push_back(id);
  for (const std::string &id : ids)
    endSession(id, "shutdown", true);
  for (auto &[transport, timer] : pending)
    transport->close();
  pending.clear();
  webrtc.clear();
  dials.clear();
  lanServer.stop();
}

void Node::Impl::emit(Json event) { host.event(event.dump()); }

void Node::Impl::emitSession(Session &session) {
  emit({{"event", "session"},
        {"session_id", session.id()},
        {"role", roleName(session.role())},
        {"state", stateName(session.state())},
        {"peer", toJson(session.peer())},
        {"transport", session.transportKind()},
        {"rtt_ms", session.rttMs()}});
}

PlaybackSnapshot Node::Impl::localSnapshot() const {
  PlaybackSnapshot now = local;
  const std::int64_t at = monotonicMs();
  now.position = projectPosition(local, at - local.timestamp);
  now.timestamp = at;
  return now;
}

void Node::Impl::publish(PlaybackSnapshot snapshot) {
  snapshot.timestamp = monotonicMs();
  const bool trackChanged = jsonString(snapshot.track, "id") != jsonString(local.track, "id") ||
                            snapshot.playing != local.playing;
  local = std::move(snapshot);
  broadcastState(false);
  if (trackChanged)
    schedulePresenceUpdate();
}

std::vector<std::shared_ptr<Session>> Node::Impl::targetSessions(bool connectedOnly) const {
  std::vector<std::shared_ptr<Session>> out;
  for (const auto &[id, session] : sessions) {
    if (session->role() == SessionRole::Target &&
        (!connectedOnly || session->state() == SessionState::Connected))
      out.push_back(session);
  }
  return out;
}

std::shared_ptr<Session> Node::Impl::controllerSession() const {
  for (const auto &[id, session] : sessions) {
    if (session->role() == SessionRole::Controller)
      return session;
  }
  return nullptr;
}

void Node::Impl::broadcastState(bool force) {
  const PlaybackSnapshot current = localSnapshot();
  const std::int64_t now = monotonicMs();
  for (const auto &session : targetSessions(true)) {
    if (force || session->lastSentAtMs == 0 ||
        significantChange(session->lastSent, current, now - session->lastSentAtMs))
      session->sendState(current);
  }
}

void Node::Impl::armHeartbeat() {
  auto strong = loop.lock();
  if (!strong || heartbeatTimer != 0)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  heartbeatTimer = strong->after(kHeartbeat, [weak] {
    auto self = weak.lock();
    if (!self)
      return;
    self->heartbeatTimer = 0;
    if (self->targetSessions(true).empty())
      return;
    // Only a playing clock drifts; a paused one needs no refresh.
    if (self->local.playing)
      self->broadcastState(true);
    self->armHeartbeat();
  });
}

Roles Node::Impl::rolesFor(Session &session) {
  std::vector<DeviceInfo> controllers;
  for (const auto &other : targetSessions(true)) {
    if (other.get() != &session)
      controllers.push_back(other->peer());
  }
  controllers.push_back(session.peer());
  return selectRoles(self, controllers);
}

Roles Node::Impl::effectiveRoles() const {
  if (auto controller = controllerSession(); controller && controller->state() == SessionState::Connected)
    return controller->roles();
  std::vector<DeviceInfo> controllers;
  for (const auto &session : targetSessions(true))
    controllers.push_back(session->peer());
  return selectRoles(self, controllers);
}

void Node::Impl::updateRoles() {
  const Roles roles = effectiveRoles();
  if (roles == lastRoles)
    return;
  lastRoles = roles;
  // Targets push renegotiated roles without touching playback.
  for (const auto &session : targetSessions(true)) {
    Roles forPeer = roles;
    session->sendRoles(forPeer);
  }
  emit({{"event", "roles"}, {"roles", toJson(roles)}});
}

void Node::Impl::sessionEvent(Session &session, const std::string &name, Json payload) {
  const std::string sid = session.id();
  if (name == "state") {
    emitSession(session);
    if (session.state() == SessionState::Connected) {
      session.reconnectDeadlineMs = 0;
      session.reconnectTries = 0;
      if (session.role() == SessionRole::Target)
        armHeartbeat();
    }
    updateRoles();
  } else if (name == "peer") {
    emitSession(session);
    updateRoles();
  } else if (name == "roles") {
    updateRoles();
  } else if (name == "remote_state") {
    emit({{"event", "remote_state"}, {"session_id", sid}, {"snapshot", std::move(payload)}});
  } else if (name == "initial_playback") {
    Json event = {{"event", "initial_playback"},
                  {"session_id", sid},
                  {"role", roleName(session.role())},
                  {"outcome", payload.value("outcome", "none")}};
    if (payload.contains("snapshot"))
      event["snapshot"] = payload["snapshot"];
    emit(std::move(event));
  } else if (name == "command") {
    const Json &command = payload["command"];
    emit({{"event", "command"},
          {"session_id", sid},
          {"from", session.peer().id},
          {"id", payload.value("id", "")},
          {"action", command.value("action", "")},
          {"args", command.value("args", Json::object())}});
  } else if (name == "command_result") {
    payload["event"] = "command_result";
    emit(std::move(payload));
  } else if (name == "rpc") {
    const std::string id = jsonString(payload, "id", 64);
    if (id.empty() || incomingRpcs.size() > 256) {
      session.sendRpcResult({{"id", id}, {"ok", false}, {"error", code::InvalidRequest}});
      return;
    }
    incomingRpcs[id] = sid;
    emit({{"event", "rpc"},
          {"session_id", sid},
          {"from", session.peer().id},
          {"id", id},
          {"method", jsonString(payload, "method", 64)},
          {"params", payload.value("params", Json::object())}});
  } else if (name == "rpc_result") {
    const std::string id = jsonString(payload, "id", 64);
    auto it = rpcs.find(id);
    if (it == rpcs.end() || it->second.sessionId != sid)
      return;
    if (auto strong = loop.lock())
      strong->cancel(it->second.timer);
    rpcs.erase(it);
    emit({{"event", "rpc_result"},
          {"id", id},
          {"ok", jsonBool(payload, "ok")},
          {"result", payload.value("result", Json())},
          {"error", jsonString(payload, "error", 128)}});
  } else if (name == "stream_end") {
    payload["event"] = "stream_end";
    payload["session_id"] = sid;
    payload.erase("type");
    emit(std::move(payload));
  }
}

void Node::Impl::linkLost(Session &session, const std::string &reason) {
  auto strong = sessions.count(session.id()) ? sessions[session.id()] : nullptr;
  if (!strong)
    return;
  failPendingRpcs(session.id(), code::HostUnavailable);
  // Frames may have died with the link; a half-sent stream cannot be resumed.
  failStreams(session.id(), code::TransportFailed);
  host.log("Connect link lost (" + session.id() + "): " + reason);
  if (!session.resolved()) {
    // Never got as far as playback; a controller asks for a fresh grant instead.
    endSession(session.id(), reason == code::Unauthorized ? reason : code::TransportFailed, false);
    return;
  }
  if (session.role() == SessionRole::Controller)
    scheduleReconnect(strong, reason);
  else
    armTargetGrace(strong);
}

void Node::Impl::sessionFinished(Session &session, const std::string &reason) {
  endSession(session.id(), reason, false);
}

void Node::Impl::endSession(const std::string &sessionId, const std::string &reason, bool notifyPeer) {
  auto it = sessions.find(sessionId);
  if (it == sessions.end())
    return;
  std::shared_ptr<Session> session = it->second;
  sessions.erase(it);
  if (notifyPeer)
    session->bye(reason);
  else
    session->dropTransport();
  if (auto found = webrtc.find(sessionId); found != webrtc.end()) {
    found->second->close();
    webrtc.erase(found);
  }
  if (auto found = dials.find(sessionId); found != dials.end()) {
    for (auto &transport : found->second)
      transport->close();
    dials.erase(found);
  }
  grants.erase(sessionId);
  flowPaused.erase(sessionId);
  failStreams(sessionId, code::NotConnected);
  failPendingRpcs(sessionId, code::HostUnavailable);
  for (auto rpc = incomingRpcs.begin(); rpc != incomingRpcs.end();)
    rpc = rpc->second == sessionId ? incomingRpcs.erase(rpc) : std::next(rpc);
  hubSend({{"type", "session.end"}, {"session_id", sessionId}});
  emit({{"event", "session_ended"},
        {"session_id", sessionId},
        {"role", roleName(session->role())},
        {"peer_id", session->peer().id},
        {"reason", reason}});
  updateRoles();
}

} // namespace orchard::connect
