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

#include <cstdint>
#include <memory>
#include <string>

// The one Orchard Connect implementation. Desktop (Qt) and Android (JNI) only
// carry strings in and out: the hub socket, playback snapshots and events.
namespace orchard::connect {

class EventLoop;

// Implemented by the platform. Every call arrives on the Connect thread.
class NodeHost {
public:
  virtual ~NodeHost() = default;
  // Writes one text message to the account hub WebSocket.
  virtual void hubSend(const std::string &text) = 0;
  // One {"event": ...} JSON object; see docs/CONNECT.md for the list.
  virtual void event(const std::string &json) = 0;
  // Binary payload from a peer: a whole AudioChunk stream (header kind "audio") or a raw data frame.
  virtual void data(const std::string &sessionId, const std::string &headerJson, std::string payload) {
    (void)sessionId;
    (void)headerJson;
    (void)payload;
  }
  virtual void log(const std::string &message) { (void)message; }
};

struct NodeConfig {
  bool enableLan = true;
  std::uint16_t lanPort = 0; // 0 picks kPreferredLanPort
  int lanAttemptMs = 2500;
  int webrtcAttemptMs = 20000;
  int reconnectWindowMs = 30000;
  // How long a target waits for a lost controller before ending the session.
  int targetGraceMs = 60000;
  int rpcTimeoutMs = 30000;
  // Tests only: advertise 127.0.0.1 so two nodes in one process find each other.
  bool advertiseLoopback = false;
};

class Node {
public:
  explicit Node(NodeHost &host, NodeConfig config = {});
  ~Node();
  Node(const Node &) = delete;
  Node &operator=(const Node &) = delete;

  // Every method below is thread-safe and returns at once.

  // The platform owns the hub WebSocket and reports its lifecycle.
  void hubOpened();
  void hubMessage(const std::string &text);
  void hubClosed();

  // DeviceInfo JSON for this device (id, name, platform, capabilities, provider sessions).
  void setDevice(const std::string &deviceJson);
  // [{name, address}] IPv4 interfaces; ranked into LAN endpoints.
  void setInterfaces(const std::string &interfacesJson);
  // The local player's state. Required whenever it changes, and before connecting.
  void publishPlayback(const std::string &snapshotJson);

  // Becomes the controller of `deviceId`.
  void connectTo(const std::string &deviceId);
  // Ends one session, or every session when `sessionId` is empty.
  void disconnect(const std::string &sessionId = {});

  // Controller intent: {action, args}. Returns the command id; the result arrives as an event.
  std::string command(const std::string &commandJson);
  // RPC to a role host: {method, params, host, timeout_ms?}. `host` is "provider:<name>",
  // "artwork", "mix" or a device id. Returns the request id.
  std::string request(const std::string &requestJson);
  // Answers an incoming rpc event: {id, ok, result} or {id, ok:false, error}.
  void respond(const std::string &responseJson);
  // Binary payload with a JSON header, e.g. an AudioChunk. False only for a malformed
  // header; a saturated link reports {"event":"flow","paused":true} instead.
  bool sendData(const std::string &sessionId, const std::string &headerJson, const std::string &payload);
  // Sends one AudioChunk stream: {stream?, track, timestamp, codec, sample_rate, channels, meta}.
  // The core chunks and paces it, then reports stream_sent or stream_failed. Returns the stream
  // id, or "" when the metadata or size is invalid. Peers receive it whole through data().
  std::string sendStream(const std::string &sessionId, const std::string &metaJson, std::string payload);

  // Closes every transport without a goodbye, as a network drop would. For tests and QA.
  void dropLinks();

private:
  struct Impl;
  template <typename Fn> void post(Fn &&fn);
  std::shared_ptr<EventLoop> m_loop;
  std::shared_ptr<Impl> m_impl;
};

} // namespace orchard::connect
