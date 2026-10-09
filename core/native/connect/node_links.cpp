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

// Getting bytes between two devices: LAN first, WebRTC through the hub when the
// LAN is unreachable, and reconnects that keep the session (and the music) alive.

#include "connect/node_impl.h"

#include <algorithm>
#include <chrono>

namespace orchard::connect {
namespace {

constexpr std::size_t kMaxPending = 16;
constexpr auto kHelloTimeout = std::chrono::milliseconds(5000);

} // namespace

void Node::Impl::startLan() {
  if (!config.enableLan)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  std::weak_ptr<EventLoop> weakLoop = loop;
  const bool listening = lanServer.start(config.lanPort, [weak, weakLoop](std::shared_ptr<LocalConnectTransport> t) {
    // Library thread: bind before attach so the hello cannot slip past.
    std::weak_ptr<ConnectTransport> weakTransport = t;
    ConnectTransport::Callbacks callbacks;
    callbacks.text = [weak, weakTransport](std::string text) {
      auto self = weak.lock();
      auto transport = weakTransport.lock();
      if (self && transport)
        self->pendingText(transport, text);
    };
    callbacks.closed = [weak, weakTransport](std::string) {
      auto self = weak.lock();
      auto transport = weakTransport.lock();
      if (self && transport)
        self->dropPending(transport);
    };
    t->bind(weakLoop, std::move(callbacks));
    if (auto strongLoop = weakLoop.lock()) {
      strongLoop->post([weak, t] {
        if (auto self = weak.lock())
          self->acceptLan(t);
      });
    }
    t->attach();
  });
  if (!listening)
    host.log("Connect LAN listener could not start; remote connections only");
  schedulePresenceUpdate();
}

void Node::Impl::acceptLan(std::shared_ptr<LocalConnectTransport> transport) {
  if (pending.size() >= kMaxPending) {
    transport->close();
    return;
  }
  auto strong = loop.lock();
  if (!strong)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  std::weak_ptr<ConnectTransport> weakTransport = transport;
  pending[transport] = strong->after(kHelloTimeout, [weak, weakTransport] {
    auto self = weak.lock();
    auto t = weakTransport.lock();
    if (self && t)
      self->dropPending(t);
  });
}

void Node::Impl::dropPending(const std::shared_ptr<ConnectTransport> &transport) {
  auto it = pending.find(transport);
  if (it == pending.end())
    return;
  if (auto strong = loop.lock())
    strong->cancel(it->second);
  pending.erase(it);
  for (auto rtc = webrtc.begin(); rtc != webrtc.end(); ++rtc) {
    if (rtc->second == transport) {
      webrtc.erase(rtc);
      break;
    }
  }
  transport->close();
}

void Node::Impl::pendingText(const std::shared_ptr<ConnectTransport> &transport, const std::string &text) {
  if (!pending.count(transport))
    return;
  auto reject = [&](const char *reason) {
    Json message = {{"type", msg::Reject}, {"code", reason}};
    stampProtocol(message);
    transport->sendText(message.dump());
    // Let the reject flush before the socket goes.
    auto strong = loop.lock();
    if (auto it = pending.find(transport); it != pending.end() && strong) {
      strong->cancel(it->second);
      std::weak_ptr<Impl> weak = weak_from_this();
      it->second = strong->after(std::chrono::milliseconds(250), [weak, transport] {
        if (auto self = weak.lock())
          self->dropPending(transport);
      });
    }
  };
  const auto hello = text.size() <= 4096 ? parseJson(text) : std::nullopt;
  if (!hello || jsonString(*hello, "type", 16) != msg::Hello)
    return reject(code::IncompatibleClient);
  // Version first: nothing else in an incompatible hello is trusted.
  if (std::string error = checkProtocol(*hello); !error.empty())
    return reject(error.c_str());

  const std::string sid = jsonString(*hello, "session_id", 80);
  const std::string deviceId = jsonString(*hello, "device_id", 64);
  if (jsonString(*hello, "target_id", 64) != self.id)
    return reject(code::Unauthorized);

  std::shared_ptr<Session> session;
  if (auto existing = sessions.find(sid); existing != sessions.end()) {
    if (existing->second->role() != SessionRole::Target || existing->second->peer().id != deviceId)
      return reject(code::Unauthorized);
    session = existing->second;
  } else {
    auto grant = grants.find(sid);
    if (grant == grants.end() || grant->second.peer.id != deviceId || jsonBool(*hello, "resume") ||
        monotonicMs() > grant->second.establishDeadlineMs)
      return reject(code::Unauthorized);
    if (auto current = controllerSession(); current && current->peer().id != deviceId)
      return reject(code::Busy);
    session = std::make_shared<Session>(loop, *this, grant->second);
    grants.erase(grant);
    sessions[sid] = session;
  }
  if (auto strong = loop.lock())
    strong->cancel(pending[transport]);
  pending.erase(transport);
  session->adoptAsTarget(transport, *hello);
}

void Node::Impl::acceptOffer(const std::string &sessionId, const Json &data) {
  Json iceServers = Json::array();
  if (auto session = sessions.find(sessionId); session != sessions.end()) {
    if (session->second->role() != SessionRole::Target)
      return;
    iceServers = session->second->grant().iceServers;
  } else if (auto grant = grants.find(sessionId); grant != grants.end()) {
    iceServers = grant->second.iceServers;
  } else {
    return;
  }
  if (auto old = webrtc.find(sessionId); old != webrtc.end()) {
    dropPending(old->second);
    webrtc.erase(sessionId);
  }
  auto transport = std::make_shared<WebRtcConnectTransport>(iceServers, false);
  std::weak_ptr<Impl> weak = weak_from_this();
  std::weak_ptr<ConnectTransport> weakTransport = transport;
  const std::string peerId = sessions.count(sessionId) ? sessions[sessionId]->peer().id : grants[sessionId].peer.id;
  ConnectTransport::Callbacks callbacks;
  callbacks.text = [weak, weakTransport](std::string text) {
    auto self = weak.lock();
    auto t = weakTransport.lock();
    if (self && t)
      self->pendingText(t, text);
  };
  callbacks.closed = [weak, weakTransport](std::string) {
    auto self = weak.lock();
    auto t = weakTransport.lock();
    if (self && t)
      self->dropPending(t);
  };
  callbacks.signal = [weak, sessionId, peerId](std::string json) {
    auto self = weak.lock();
    const auto data = parseJson(json);
    if (self && data)
      self->hubSend({{"type", "signal"}, {"to", peerId}, {"session_id", sessionId}, {"data", *data}});
  };
  transport->bind(loop, std::move(callbacks));
  webrtc[sessionId] = transport;
  if (auto strong = loop.lock()) {
    pending[transport] = strong->after(std::chrono::milliseconds(config.webrtcAttemptMs), [weak, weakTransport] {
      auto self = weak.lock();
      auto t = weakTransport.lock();
      if (self && t)
        self->dropPending(t);
    });
  }
  transport->start();
  transport->applySignal(data);
}

void Node::Impl::dial(const std::shared_ptr<Session> &session) {
  const std::uint64_t attempt = ++session->attempt;
  const std::string sid = session->id();
  session->setState(session->resolved() ? SessionState::Reconnecting : SessionState::Connecting);
  if (auto old = dials.find(sid); old != dials.end()) {
    for (auto &transport : old->second)
      transport->close();
    dials.erase(old);
  }
  std::vector<LanEndpoint> endpoints = session->grant().peerLan;
  if (!config.enableLan || endpoints.empty()) {
    startWebRtc(session, attempt);
    return;
  }
  std::weak_ptr<Impl> weak = weak_from_this();
  std::weak_ptr<Session> weakSession = session;
  endpoints.resize(std::min<std::size_t>(endpoints.size(), 4));
  for (const LanEndpoint &endpoint : endpoints) {
    auto transport = std::make_shared<LocalConnectTransport>();
    std::weak_ptr<ConnectTransport> weakTransport = transport;
    ConnectTransport::Callbacks callbacks;
    callbacks.opened = [weak, weakSession, weakTransport, attempt] {
      auto self = weak.lock();
      auto s = weakSession.lock();
      auto t = weakTransport.lock();
      if (self && s && t)
        self->dialOpened(s, attempt, t);
    };
    callbacks.closed = [weak, weakSession, weakTransport, attempt](std::string) {
      auto self = weak.lock();
      auto s = weakSession.lock();
      auto t = weakTransport.lock();
      if (!self || !s || !t)
        return;
      auto &racing = self->dials[s->id()];
      racing.erase(std::remove(racing.begin(), racing.end(), t), racing.end());
      // Every LAN address refused: no point waiting out the timer.
      if (racing.empty() && s->attempt == attempt && !s->hasTransport())
        self->startWebRtc(s, attempt);
    };
    transport->bind(loop, std::move(callbacks));
    dials[sid].push_back(transport);
    transport->open(endpoint.host, endpoint.port);
  }
  if (auto strong = loop.lock()) {
    strong->after(std::chrono::milliseconds(config.lanAttemptMs), [weak, weakSession, attempt] {
      auto self = weak.lock();
      auto s = weakSession.lock();
      if (self && s && s->attempt == attempt && !s->hasTransport())
        self->startWebRtc(s, attempt);
    });
  }
}

void Node::Impl::startWebRtc(const std::shared_ptr<Session> &session, std::uint64_t attempt) {
  if (session->webrtcAttempt == attempt)
    return;
  session->webrtcAttempt = attempt;
  if (!hubConnected) {
    attemptFailed(session, attempt, "hub_offline");
    return;
  }
  const std::string sid = session->id();
  const std::string peerId = session->peer().id;
  auto transport = std::make_shared<WebRtcConnectTransport>(session->grant().iceServers, true);
  std::weak_ptr<Impl> weak = weak_from_this();
  std::weak_ptr<Session> weakSession = session;
  std::weak_ptr<ConnectTransport> weakTransport = transport;
  ConnectTransport::Callbacks callbacks;
  callbacks.opened = [weak, weakSession, weakTransport, attempt] {
    auto self = weak.lock();
    auto s = weakSession.lock();
    auto t = weakTransport.lock();
    if (self && s && t)
      self->dialOpened(s, attempt, t);
  };
  callbacks.closed = [weak, weakSession, attempt](std::string reason) {
    auto self = weak.lock();
    auto s = weakSession.lock();
    if (self && s)
      self->attemptFailed(s, attempt, reason);
  };
  callbacks.signal = [weak, sid, peerId](std::string json) {
    auto self = weak.lock();
    const auto data = parseJson(json);
    if (self && data)
      self->hubSend({{"type", "signal"}, {"to", peerId}, {"session_id", sid}, {"data", *data}});
  };
  transport->bind(loop, std::move(callbacks));
  if (auto old = webrtc.find(sid); old != webrtc.end())
    old->second->close();
  webrtc[sid] = transport;
  transport->start();
  if (auto strong = loop.lock()) {
    strong->after(std::chrono::milliseconds(config.webrtcAttemptMs), [weak, weakSession, weakTransport, attempt] {
      auto self = weak.lock();
      auto s = weakSession.lock();
      if (!self || !s || s->attempt != attempt || s->hasTransport())
        return;
      if (auto t = weakTransport.lock())
        t->close();
      self->attemptFailed(s, attempt, code::Timeout);
    });
  }
}

void Node::Impl::dialOpened(const std::shared_ptr<Session> &session, std::uint64_t attempt,
                            const std::shared_ptr<ConnectTransport> &transport) {
  if (session->attempt != attempt || session->hasTransport() || !sessions.count(session->id())) {
    transport->close();
    return;
  }
  // First open link wins; the rest of the race is called off.
  if (auto racing = dials.find(session->id()); racing != dials.end()) {
    for (auto &other : racing->second) {
      if (other != transport)
        other->close();
    }
    dials.erase(racing);
  }
  if (auto rtc = webrtc.find(session->id()); rtc != webrtc.end() && rtc->second != transport) {
    rtc->second->close();
    webrtc.erase(rtc);
  }
  session->adoptAsController(transport);
}

void Node::Impl::attemptFailed(const std::shared_ptr<Session> &session, std::uint64_t attempt,
                               const std::string &reason) {
  if (session->attempt != attempt || session->hasTransport() || !sessions.count(session->id()))
    return;
  if (!session->resolved()) {
    endSession(session->id(), code::TransportFailed, false);
    return;
  }
  scheduleReconnect(session, reason);
}

void Node::Impl::scheduleReconnect(const std::shared_ptr<Session> &session, const std::string &reason) {
  const std::int64_t now = monotonicMs();
  if (session->reconnectDeadlineMs == 0) {
    session->reconnectDeadlineMs = now + config.reconnectWindowMs;
    session->reconnectTries = 0;
  }
  if (now >= session->reconnectDeadlineMs) {
    endSession(session->id(), "target_lost", false);
    return;
  }
  host.log("Connect reconnecting (" + session->id() + ") after " + reason);
  session->setState(SessionState::Reconnecting);
  const int delayMs = std::min(250 << std::min(session->reconnectTries, 5), 4000);
  ++session->reconnectTries;
  auto strong = loop.lock();
  if (!strong)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  std::weak_ptr<Session> weakSession = session;
  strong->after(std::chrono::milliseconds(delayMs), [weak, weakSession] {
    auto self = weak.lock();
    auto s = weakSession.lock();
    if (self && s && !s->hasTransport() && self->sessions.count(s->id()))
      self->dial(s);
  });
}

void Node::Impl::armTargetGrace(const std::shared_ptr<Session> &session) {
  // The music keeps playing; only the controller's view of it is gone.
  if (session->reconnectDeadlineMs == 0)
    session->reconnectDeadlineMs = monotonicMs() + config.targetGraceMs;
  session->setState(SessionState::Reconnecting);
  auto strong = loop.lock();
  if (!strong)
    return;
  std::weak_ptr<Impl> weak = weak_from_this();
  std::weak_ptr<Session> weakSession = session;
  const std::int64_t wait = std::max<std::int64_t>(session->reconnectDeadlineMs - monotonicMs(), 0);
  strong->after(std::chrono::milliseconds(wait), [weak, weakSession] {
    auto self = weak.lock();
    auto s = weakSession.lock();
    if (self && s && s->state() != SessionState::Connected && self->sessions.count(s->id()) &&
        monotonicMs() >= s->reconnectDeadlineMs)
      self->endSession(s->id(), "controller_lost", false);
  });
}

} // namespace orchard::connect
