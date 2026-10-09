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

// Binary traffic: raw data frames with host-driven flow events, and AudioChunk streams the
// core paces itself so keepalives still fit between chunks.
namespace orchard::connect {
namespace {

constexpr std::size_t kFlowHigh = 4 * 1024 * 1024;
constexpr std::size_t kFlowLow = 1024 * 1024;
constexpr auto kDrainRetry = std::chrono::milliseconds(20);

} // namespace

void Node::Impl::sessionData(Session &session, Json header, std::string payload) {
  if (jsonString(header, "kind", 16) != "audio") {
    host.data(session.id(), header.dump(), std::move(payload));
    return;
  }
  std::string stream;
  std::string error;
  auto finished = inbound[session.id()].accept(header, std::move(payload), stream, error);
  if (!error.empty()) {
    emit({{"event", "stream_failed"}, {"session_id", session.id()}, {"stream", stream}, {"error", error}});
    return;
  }
  if (finished)
    host.data(session.id(), finished->header.dump(), std::move(finished->payload));
}

void Node::Impl::sendData(const std::string &sessionId, Json header, std::string payload) {
  auto it = sessions.find(sessionId);
  if (it == sessions.end() || !it->second->sendData(header, payload)) {
    emit({{"event", "flow"}, {"session_id", sessionId}, {"paused", true}, {"error", code::NotConnected}});
    return;
  }
  if (it->second->bufferedAmount() > kFlowHigh && flowPaused.insert(sessionId).second) {
    emit({{"event", "flow"}, {"session_id", sessionId}, {"paused", true}});
    watchFlow(sessionId);
  }
}

void Node::Impl::watchFlow(const std::string &sessionId) {
  auto strong = loop.lock();
  if (!strong)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  strong->after(std::chrono::milliseconds(50), [weak, sessionId] {
    auto self = weak.lock();
    if (!self || !self->flowPaused.count(sessionId))
      return;
    auto it = self->sessions.find(sessionId);
    if (it != self->sessions.end() && it->second->bufferedAmount() > kFlowLow) {
      self->watchFlow(sessionId);
      return;
    }
    self->flowPaused.erase(sessionId);
    self->emit({{"event", "flow"}, {"session_id", sessionId}, {"paused", false}});
  });
}

void Node::Impl::sendStream(OutgoingStream stream) {
  auto it = sessions.find(stream.sessionId);
  if (it == sessions.end() || it->second->state() != SessionState::Connected) {
    emit({{"event", "stream_failed"},
          {"session_id", stream.sessionId},
          {"stream", stream.meta["stream"]},
          {"error", code::NotConnected}});
    return;
  }
  const std::string sessionId = stream.sessionId;
  outbox[sessionId].push_back(std::move(stream));
  drainStreams(sessionId);
}

void Node::Impl::drainStreams(const std::string &sessionId) {
  auto queue = outbox.find(sessionId);
  auto it = sessions.find(sessionId);
  if (queue == outbox.end() || it == sessions.end())
    return;
  // One chunk past the watermark at most, so pings and RPCs never wait behind a whole song.
  while (!queue->second.empty() && it->second->bufferedAmount() < kFlowHigh) {
    OutgoingStream &stream = queue->second.front();
    auto [header, chunk] = stream.next();
    if (!it->second->sendData(header, chunk)) {
      failStreams(sessionId, code::NotConnected);
      return;
    }
    if (stream.done()) {
      emit({{"event", "stream_sent"}, {"session_id", sessionId}, {"stream", stream.meta["stream"]}});
      queue->second.pop_front();
    }
  }
  if (queue->second.empty()) {
    outbox.erase(queue);
    return;
  }
  auto strong = loop.lock();
  if (!strong || !draining.insert(sessionId).second)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  strong->after(kDrainRetry, [weak, sessionId] {
    if (auto self = weak.lock()) {
      self->draining.erase(sessionId);
      self->drainStreams(sessionId);
    }
  });
}

void Node::Impl::failStreams(const std::string &sessionId, const std::string &error) {
  std::vector<std::string> failed;
  if (auto queue = outbox.find(sessionId); queue != outbox.end()) {
    for (const auto &stream : queue->second)
      failed.push_back(jsonString(stream.meta, "stream", 64));
    outbox.erase(queue);
  }
  if (auto partial = inbound.find(sessionId); partial != inbound.end()) {
    for (auto &stream : partial->second.clear())
      failed.push_back(std::move(stream));
    inbound.erase(partial);
  }
  for (const auto &stream : failed)
    emit({{"event", "stream_failed"}, {"session_id", sessionId}, {"stream", stream}, {"error", error}});
}

} // namespace orchard::connect
