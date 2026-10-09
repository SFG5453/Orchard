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

// The account hub: presence, session grants and WebRTC signaling. A grant is
// the only thing that lets two devices talk; signaling alone authorizes nothing.

#include "connect/node_impl.h"

#include <algorithm>
#include <chrono>

namespace orchard::connect {
namespace {

constexpr std::int64_t kMaxGrantSeconds = 120;
constexpr auto kRequestTimeout = std::chrono::milliseconds(15000);

} // namespace

void Node::Impl::hubOpened() {
  hubConnected = true;
  sendPresence(true);
  emit({{"event", "hub"}, {"connected", true}});
}

void Node::Impl::hubClosed() {
  hubConnected = false;
  hubWelcomed = false;
  presence.clear();
  // Established LAN sessions keep running; only discovery and WebRTC need the hub.
  emit({{"event", "devices"}, {"devices", Json::array()}});
  emit({{"event", "hub"}, {"connected", false}});
}

void Node::Impl::hubSend(Json message) {
  if (hubConnected)
    host.hubSend(message.dump());
}

std::vector<LanEndpoint> Node::Impl::lanEndpoints() const {
  std::vector<LanEndpoint> out;
  if (lanServer.port() == 0)
    return out;
  if (config.advertiseLoopback)
    out.push_back({"127.0.0.1", lanServer.port()});
  for (const std::string &address : rankLanAddresses(interfaces)) {
    if (out.size() >= 4)
      break;
    out.push_back({address, lanServer.port()});
  }
  return out;
}

void Node::Impl::sendPresence(bool initial) {
  if (!hubConnected || self.id.empty())
    return;
  DeviceInfo announced = self;
  if (local.hasMedia()) {
    announced.currentlyPlaying = {{"id", jsonString(local.track, "id")},
                                  {"title", jsonString(local.track, "title")},
                                  {"artist", jsonString(local.track, "artist")},
                                  {"playing", local.playing}};
  }
  lastPresenceTrack = announced.currentlyPlaying;
  Json message = {{"type", initial ? "hello" : "update"},
                  {"device", toJson(announced)},
                  {"lan", toJson(lanEndpoints())}};
  stampProtocol(message);
  hubSend(std::move(message));
}

void Node::Impl::schedulePresenceUpdate() {
  auto strong = loop.lock();
  if (!strong || presenceTimer != 0 || !hubConnected)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  presenceTimer = strong->after(std::chrono::milliseconds(1000), [weak] {
    if (auto self = weak.lock()) {
      self->presenceTimer = 0;
      self->sendPresence(false);
    }
  });
}

void Node::Impl::hubMessage(const std::string &text) {
  const auto message = parseJson(text);
  if (!message || !message->is_object())
    return;
  const std::string type = jsonString(*message, "type", 32);
  if (type == "welcome") {
    hubWelcomed = true;
  } else if (type == "presence") {
    handlePresence(*message);
  } else if (type == "session.grant") {
    handleGrant(*message);
  } else if (type == "signal") {
    handleSignal(*message);
  } else if (type == "error" || type == "session.declined") {
    handleHubError(*message);
  }
}

void Node::Impl::handlePresence(const Json &message) {
  presence.clear();
  Json devices = Json::array();
  if (message.contains("devices") && message["devices"].is_array()) {
    for (const Json &item : message["devices"]) {
      Presence entry;
      entry.device = deviceFromJson(item.value("device", Json::object()));
      entry.lan = endpointsFromJson(item.value("lan", Json::array()));
      entry.compatible = entry.device.protocolMajor == kProtocolMajor;
      if (entry.device.id.empty() || entry.device.id == self.id || presence.size() >= 64)
        continue;
      Json shown = toJson(entry.device);
      shown["online"] = true;
      shown["compatible"] = entry.compatible;
      for (const auto &[sid, session] : sessions) {
        if (session->peer().id == entry.device.id) {
          shown["session_id"] = sid;
          // Fresh endpoints for the next reconnect attempt.
          session->grant().peerLan = entry.lan;
        }
      }
      devices.push_back(std::move(shown));
      presence[entry.device.id] = std::move(entry);
    }
  }
  emit({{"event", "devices"}, {"devices", std::move(devices)}});
}

void Node::Impl::connectTo(const std::string &deviceId) {
  auto fail = [&](const char *error) {
    emit({{"event", "error"}, {"code", error}, {"device_id", deviceId}});
  };
  if (!hubConnected)
    return fail("hub_offline");
  const auto found = presence.find(deviceId);
  if (found == presence.end())
    return fail(code::PeerOffline);
  if (!found->second.compatible)
    return fail(code::IncompatibleProtocol);
  if (auto current = controllerSession()) {
    if (current->peer().id == deviceId)
      return;
    endSession(current->id(), "switched_target", true);
  }
  // Becoming a controller ends this device's time as anyone's target.
  for (const auto &session : targetSessions(false))
    endSession(session->id(), "role_change", true);

  const std::string requestId = newId();
  requestedPeers[requestId] = deviceId;
  hubSend({{"type", "session.request"}, {"request_id", requestId}, {"to", deviceId}});
  emit({{"event", "session"},
        {"session_id", ""},
        {"request_id", requestId},
        {"role", "controller"},
        {"state", stateName(SessionState::Discovering)},
        {"peer", toJson(found->second.device)},
        {"transport", ""},
        {"rtt_ms", 0}});
  if (auto strong = loop.lock()) {
    std::weak_ptr<Impl> weak = weak_from_this();
    requestTimers[requestId] = strong->after(kRequestTimeout, [weak, requestId, deviceId] {
      auto self = weak.lock();
      if (!self || !self->requestedPeers.erase(requestId))
        return;
      self->requestTimers.erase(requestId);
      self->emit({{"event", "error"}, {"code", code::Timeout}, {"device_id", deviceId}});
    });
  }
}

void Node::Impl::handleGrant(const Json &message) {
  Grant grant;
  grant.sessionId = jsonString(message, "session_id", 80);
  const auto key = base64Decode(jsonString(message, "key", 128));
  const std::string role = jsonString(message, "role", 16);
  grant.peer = deviceFromJson(message.value("peer", Json::object()));
  grant.peerLan = endpointsFromJson(message.value("peer_lan", Json::array()));
  if (message.contains("ice_servers") && message["ice_servers"].is_array())
    grant.iceServers = message["ice_servers"];
  const auto lifetime = std::clamp<std::int64_t>(
      static_cast<std::int64_t>(jsonNumber(message, "expires_in", 60)), 5, kMaxGrantSeconds);
  grant.establishDeadlineMs = monotonicMs() + lifetime * 1000;
  if (grant.sessionId.empty() || !key || key->size() != kSessionKeyBytes || grant.peer.id.empty() ||
      grant.peer.protocolMajor != kProtocolMajor || sessions.count(grant.sessionId))
    return;
  grant.key = *key;

  if (role == "controller") {
    const std::string requestId = jsonString(message, "request_id", 64);
    const auto requested = requestedPeers.find(requestId);
    if (requested == requestedPeers.end() || requested->second != grant.peer.id)
      return;
    requestedPeers.erase(requested);
    if (auto timer = requestTimers.find(requestId); timer != requestTimers.end()) {
      if (auto strong = loop.lock())
        strong->cancel(timer->second);
      requestTimers.erase(timer);
    }
    grant.role = SessionRole::Controller;
    auto session = std::make_shared<Session>(loop, *this, std::move(grant));
    sessions[session->id()] = session;
    emitSession(*session);
    dial(session);
    return;
  }
  if (role != "target")
    return;
  if (auto current = controllerSession()) {
    // The device this one controls wants to take over instead: let it.
    if (current->peer().id == grant.peer.id) {
      endSession(current->id(), "peer_took_control", true);
    } else {
      hubSend({{"type", "session.decline"}, {"session_id", grant.sessionId}, {"code", code::Busy}});
      return;
    }
  }
  grant.role = SessionRole::Target;
  const std::string sid = grant.sessionId;
  grants[sid] = std::move(grant);
  if (auto strong = loop.lock()) {
    std::weak_ptr<Impl> weak = weak_from_this();
    strong->after(std::chrono::seconds(kMaxGrantSeconds), [weak, sid] {
      if (auto self = weak.lock())
        self->grants.erase(sid);
    });
  }
}

void Node::Impl::handleSignal(const Json &message) {
  const std::string sid = jsonString(message, "session_id", 80);
  const std::string from = jsonString(message, "from", 64);
  const Json data = message.value("data", Json::object());
  if (!data.is_object())
    return;
  std::string expectedPeer;
  if (auto session = sessions.find(sid); session != sessions.end())
    expectedPeer = session->second->peer().id;
  else if (auto grant = grants.find(sid); grant != grants.end())
    expectedPeer = grant->second.peer.id;
  if (expectedPeer.empty() || expectedPeer != from)
    return;
  const bool isOffer = jsonString(data, "kind", 32) == "description" && jsonString(data, "type", 16) == "offer";
  if (isOffer) {
    acceptOffer(sid, data);
    return;
  }
  if (auto transport = webrtc.find(sid); transport != webrtc.end())
    transport->second->applySignal(data);
}

void Node::Impl::handleHubError(const Json &message) {
  const std::string errorCode = jsonString(message, "code", 64);
  const std::string requestId = jsonString(message, "request_id", 64);
  const std::string sid = jsonString(message, "session_id", 80);
  std::string deviceId;
  if (auto requested = requestedPeers.find(requestId); requested != requestedPeers.end()) {
    deviceId = requested->second;
    requestedPeers.erase(requested);
  }
  if (!sid.empty() && sessions.count(sid))
    endSession(sid, errorCode.empty() ? code::Unauthorized : errorCode, false);
  emit({{"event", "error"},
        {"code", errorCode.empty() ? "hub_error" : errorCode},
        {"message", jsonString(message, "message", 256)},
        {"device_id", deviceId}});
}

} // namespace orchard::connect
