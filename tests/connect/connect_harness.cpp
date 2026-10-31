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

#include "connect_harness.h"

#include <chrono>
#include <thread>

namespace connect_test {

using namespace orchard::connect;

Json track(const std::string &id, const std::string &title) {
  return {{"id", id}, {"title", title}, {"artist", "Artist"}, {"album", "Album"}, {"duration", 200.0},
          {"provider", "youtube"}};
}

Json playing(const Json &item, double position, Json queue) {
  return {{"track", item},          {"position", position}, {"duration", 200.0}, {"playing", true},
          {"queue", std::move(queue)}, {"volume", 1.0},       {"repeat", "off"},   {"shuffle", false}};
}

bool isSession(const Json &event, const std::string &role, const std::string &state) {
  return event.value("event", "") == "session" && event.value("role", "") == role &&
         event.value("state", "") == state;
}

TestDevice::TestDevice(FakeHub &hub, const std::string &id, const std::string &platform, NodeConfig config)
    : m_hub(hub), m_id(id) {
  const bool desktop = platform != "android";
  m_device = {{"id", id},
              {"name", id},
              {"platform", platform},
              {"can_render_audio", true},
              {"can_mix_audio", desktop},
              {"can_fetch_artwork", desktop},
              {"providers", {"youtube", "qobuz"}},
              {"provider_sessions", {{"youtube", true}, {"qobuz", false}}},
              {"audio_transports", {"pcm_s16"}}};
  m_playback = Json::object();
  config.advertiseLoopback = true;
  m_node = std::make_unique<Node>(*this, config);
  m_node->setDevice(m_device.dump());
  m_node->publishPlayback(m_playback.dump());
  m_hub.attach(this);
}

TestDevice::~TestDevice() {
  m_hub.detach(this);
  m_node.reset();
}

void TestDevice::setDevice(const std::function<void(Json &)> &edit) {
  std::lock_guard lock(m_mutex);
  edit(m_device);
  m_node->setDevice(m_device.dump());
}

void TestDevice::setPlayback(Json snapshot) {
  std::lock_guard lock(m_mutex);
  m_playback = std::move(snapshot);
  publishLocked();
}

Json TestDevice::playback() const {
  std::lock_guard lock(m_mutex);
  return m_playback;
}

void TestDevice::publishLocked() { m_node->publishPlayback(m_playback.dump()); }

void TestDevice::hubSend(const std::string &text) { m_hub.receive(this, text); }

void TestDevice::data(const std::string &, const std::string &headerJson, std::string payload) {
  std::lock_guard lock(m_mutex);
  m_data.emplace_back(Json::parse(headerJson), std::move(payload));
}

std::vector<std::pair<Json, std::string>> TestDevice::received() const {
  std::lock_guard lock(m_mutex);
  return m_data;
}

void TestDevice::event(const std::string &json) {
  Json event = Json::parse(json);
  std::lock_guard lock(m_mutex);
  m_events.push_back(event);
  const std::string name = event.value("event", "");
  if (name == "initial_playback") {
    const std::string outcome = event.value("outcome", "none");
    if (event.value("role", "") == "target" && outcome == "transferred") {
      m_playback = event["snapshot"];
      publishLocked();
    } else if (event.value("role", "") == "controller" && outcome != "none") {
      // The controller's own speakers go quiet while it mirrors the target.
      m_playback["playing"] = false;
      publishLocked();
    }
  } else if (name == "command") {
    applyCommand(event.value("action", ""), event.value("args", Json::object()));
    publishLocked();
  } else if (name == "rpc" && answerRpcs) {
    m_node->respond(Json{{"id", event["id"]},
                         {"ok", true},
                         {"result", {{"method", event["method"]}, {"served_by", m_id}, {"params", event["params"]}}}}
                        .dump());
  }
}

void TestDevice::applyCommand(const std::string &action, const Json &args) {
  Json &p = m_playback;
  if (!p.contains("queue"))
    p["queue"] = Json::array();
  if (action == "play")
    p["playing"] = p.contains("track") && p["track"].is_object();
  else if (action == "pause")
    p["playing"] = false;
  else if (action == "toggle")
    p["playing"] = !p.value("playing", false);
  else if (action == "seek")
    p["position"] = args["position"];
  else if (action == "set_volume")
    p["volume"] = args["volume"];
  else if (action == "set_shuffle")
    p["shuffle"] = args["enabled"];
  else if (action == "set_repeat")
    p["repeat"] = args["mode"];
  else if (action == "next" && !p["queue"].empty()) {
    p["track"] = p["queue"][0];
    p["queue"].erase(0);
    p["position"] = 0.0;
  } else if (action == "play_track") {
    p["track"] = args["track"];
    p["queue"] = Json::array();
    p["position"] = args["position"];
    p["playing"] = args["play"];
  } else if (action == "enqueue") {
    if (args.value("next", false))
      p["queue"].insert(p["queue"].begin(), args["track"]);
    else
      p["queue"].push_back(args["track"]);
  } else if (action == "remove_queue_item" && args["index"].get<std::size_t>() < p["queue"].size()) {
    p["queue"].erase(args["index"].get<std::size_t>());
  } else if (action == "clear_queue") {
    p["queue"] = Json::array();
  }
}

std::size_t TestDevice::mark() const {
  std::lock_guard lock(m_mutex);
  return m_events.size();
}

Json TestDevice::waitEvent(const std::function<bool(const Json &)> &match, int timeoutMs, std::size_t from) {
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
  while (std::chrono::steady_clock::now() < deadline) {
    {
      std::lock_guard lock(m_mutex);
      for (std::size_t i = from; i < m_events.size(); ++i) {
        if (match(m_events[i]))
          return m_events[i];
      }
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }
  // An empty object, so reading fields off a timed-out wait fails the test cleanly.
  return Json::object();
}

int TestDevice::countEvents(const std::function<bool(const Json &)> &match) const {
  std::lock_guard lock(m_mutex);
  int count = 0;
  for (const Json &event : m_events)
    count += match(event) ? 1 : 0;
  return count;
}

void FakeHub::attach(TestDevice *device) {
  {
    std::lock_guard lock(m_mutex);
    m_clients[device->id()].device = device;
  }
  device->node().hubOpened();
}

void FakeHub::detach(TestDevice *device) {
  std::lock_guard lock(m_mutex);
  m_clients.erase(device->id());
  broadcastPresenceLocked();
}

int FakeHub::lanPortOf(const std::string &id) {
  std::lock_guard lock(m_mutex);
  auto it = m_clients.find(id);
  if (it == m_clients.end() || !it->second.lan.is_array() || it->second.lan.empty())
    return 0;
  return it->second.lan[0].value("port", 0);
}

void FakeHub::send(TestDevice *to, const Json &message) {
  if (to)
    to->node().hubMessage(message.dump());
}

void FakeHub::broadcastPresenceLocked() {
  Json devices = Json::array();
  for (const auto &[id, client] : m_clients) {
    if (client.announced)
      devices.push_back({{"device", client.info}, {"lan", client.lan}});
  }
  for (const auto &[id, client] : m_clients) {
    if (client.announced)
      send(client.device, {{"type", "presence"}, {"devices", devices}});
  }
}

void FakeHub::receive(TestDevice *from, const std::string &text) {
  const Json message = Json::parse(text);
  const std::string type = message.value("type", "");
  std::lock_guard lock(m_mutex);
  auto self = m_clients.find(from->id());
  if (self == m_clients.end())
    return;
  if (type == "hello" || type == "update") {
    if (std::string error = checkProtocol(message); !error.empty()) {
      send(from, {{"type", "error"}, {"code", error}});
      return;
    }
    self->second.info = message["device"];
    self->second.lan = message.value("lan", Json::array());
    self->second.announced = true;
    if (type == "hello")
      send(from, {{"type", "welcome"}, {"device_id", from->id()}});
    broadcastPresenceLocked();
  } else if (type == "session.request") {
    const std::string to = message.value("to", "");
    auto peer = m_clients.find(to);
    if (peer == m_clients.end() || !peer->second.announced) {
      send(from, {{"type", "error"}, {"code", "peer_offline"}, {"request_id", message["request_id"]}});
      return;
    }
    const std::string sid = newId();
    const std::string key = base64Encode(randomBytes(32));
    m_requesters[sid] = from->id();
    send(from, {{"type", "session.grant"}, {"role", "controller"}, {"session_id", sid}, {"key", key},
                {"request_id", message["request_id"]}, {"peer", peer->second.info}, {"peer_lan", peer->second.lan},
                {"ice_servers", iceServers}, {"expires_in", 60}});
    if (to != blackhole)
      send(peer->second.device, {{"type", "session.grant"}, {"role", "target"}, {"session_id", sid}, {"key", key},
                                 {"peer", self->second.info}, {"peer_lan", self->second.lan},
                                 {"ice_servers", iceServers}, {"expires_in", 60}});
  } else if (type == "signal") {
    auto peer = m_clients.find(message.value("to", ""));
    if (peer != m_clients.end())
      send(peer->second.device, {{"type", "signal"}, {"from", from->id()}, {"session_id", message["session_id"]},
                                 {"data", message["data"]}});
  } else if (type == "session.decline") {
    const std::string sid = message.value("session_id", "");
    auto requester = m_clients.find(m_requesters[sid]);
    if (requester != m_clients.end())
      send(requester->second.device,
           {{"type", "session.declined"}, {"session_id", sid}, {"code", message.value("code", "")}});
  }
}

} // namespace connect_test
