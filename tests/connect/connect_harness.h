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

#include "connect/node.h"
#include "connect/protocol.h"

#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace connect_test {

using orchard::connect::Json;

class FakeHub;

// One Orchard install: a real Node plus a toy player that obeys commands the
// way the desktop and Android adapters do.
class TestDevice final : public orchard::connect::NodeHost {
public:
  TestDevice(FakeHub &hub, const std::string &id, const std::string &platform,
             orchard::connect::NodeConfig config = {});
  ~TestDevice() override;

  void hubSend(const std::string &text) override;
  void event(const std::string &json) override;
  void data(const std::string &sessionId, const std::string &headerJson, std::string payload) override;

  [[nodiscard]] const std::string &id() const { return m_id; }
  orchard::connect::Node &node() { return *m_node; }

  // Capabilities beyond the platform defaults.
  void setDevice(const std::function<void(Json &)> &edit);
  void setPlayback(Json snapshot);
  [[nodiscard]] Json playback() const;

  // Events are kept in arrival order; `from` skips earlier ones. Empty on timeout.
  [[nodiscard]] std::size_t mark() const;
  Json waitEvent(const std::function<bool(const Json &)> &match, int timeoutMs = 10000, std::size_t from = 0);
  [[nodiscard]] int countEvents(const std::function<bool(const Json &)> &match) const;
  [[nodiscard]] std::vector<std::pair<Json, std::string>> received() const;

  bool answerRpcs = true;

private:
  void applyCommand(const std::string &action, const Json &args);
  void publishLocked();

  FakeHub &m_hub;
  std::string m_id;
  Json m_device;
  mutable std::mutex m_mutex;
  Json m_playback;
  std::vector<Json> m_events;
  std::vector<std::pair<Json, std::string>> m_data;
  std::unique_ptr<orchard::connect::Node> m_node;
};

// The account hub's contract in-process: presence, grants, signal relay.
class FakeHub {
public:
  void attach(TestDevice *device);
  void detach(TestDevice *device);
  void receive(TestDevice *from, const std::string &text);
  // First advertised LAN port of a device, or 0 before its presence arrives.
  int lanPortOf(const std::string &id);

  Json iceServers = Json::array();
  // Grants to this device id are not delivered, to model a peer that never answers.
  std::string blackhole;

private:
  struct Client {
    TestDevice *device = nullptr;
    Json info;
    Json lan = Json::array();
    bool announced = false;
  };
  void broadcastPresenceLocked();
  static void send(TestDevice *to, const Json &message);

  std::mutex m_mutex;
  std::map<std::string, Client> m_clients;
  std::map<std::string, std::string> m_requesters; // session id -> controller id
};

Json track(const std::string &id, const std::string &title);
Json playing(const Json &track, double position, Json queue = Json::array());

bool isSession(const Json &event, const std::string &role, const std::string &state);

} // namespace connect_test
