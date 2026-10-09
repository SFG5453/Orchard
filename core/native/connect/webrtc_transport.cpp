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

#include "connect/webrtc_transport.h"

#include <rtc/rtc.hpp>

#include <variant>

namespace orchard::connect {
namespace {

std::vector<std::string> urlsOf(const Json &server) {
  std::vector<std::string> urls;
  if (!server.is_object() || !server.contains("urls"))
    return urls;
  const Json &value = server["urls"];
  if (value.is_string())
    urls.push_back(value.get<std::string>());
  else if (value.is_array()) {
    for (const Json &url : value) {
      if (url.is_string())
        urls.push_back(url.get<std::string>());
    }
  }
  return urls;
}

} // namespace

std::vector<rtc::IceServer> parseIceServers(const Json &iceServers) {
  std::vector<rtc::IceServer> out;
  int turnCount = 0;
  if (!iceServers.is_array())
    return out;
  for (const Json &server : iceServers) {
    for (const std::string &url : urlsOf(server)) {
      try {
        rtc::IceServer parsed(url);
        if (parsed.type == rtc::IceServer::Type::Turn) {
          if (parsed.relayType != rtc::IceServer::RelayType::TurnUdp || turnCount >= 2)
            continue;
          parsed.username = jsonString(server, "username", 512);
          parsed.password = jsonString(server, "credential", 512);
          ++turnCount;
        }
        out.push_back(std::move(parsed));
      } catch (const std::exception &) {
        // An unknown scheme from a newer service is skipped, not fatal.
      }
    }
  }
  return out;
}

WebRtcConnectTransport::WebRtcConnectTransport(const Json &iceServers, bool offerer, std::string label,
                                               bool strictLabel)
    : m_offerer(offerer), m_label(std::move(label)), m_strictLabel(strictLabel) {
  rtc::Configuration config;
  config.iceServers = parseIceServers(iceServers);
  m_peer = std::make_shared<rtc::PeerConnection>(config);
}

WebRtcConnectTransport::~WebRtcConnectTransport() {
  if (auto current = channel())
    current->resetCallbacks();
  if (m_peer) {
    m_peer->resetCallbacks();
    m_peer->close();
  }
}

std::shared_ptr<rtc::DataChannel> WebRtcConnectTransport::channel() const {
  std::lock_guard lock(m_mutex);
  return m_channel;
}

void WebRtcConnectTransport::fail(const std::string &reason) {
  if (!m_closed.exchange(true))
    emitClosed(reason);
}

void WebRtcConnectTransport::wireChannel(const std::shared_ptr<rtc::DataChannel> &channel) {
  std::weak_ptr<WebRtcConnectTransport> weak = weak_from_this();
  channel->onOpen([weak] {
    if (auto self = weak.lock())
      self->emitOpened();
  });
  channel->onMessage([weak](rtc::message_variant data) {
    auto self = weak.lock();
    if (!self)
      return;
    if (auto *text = std::get_if<std::string>(&data))
      self->emitText(std::move(*text));
    else if (auto *binary = std::get_if<rtc::binary>(&data))
      self->emitBinary(std::string(reinterpret_cast<const char *>(binary->data()), binary->size()));
  });
  channel->onClosed([weak] {
    if (auto self = weak.lock())
      self->fail("webrtc_channel_closed");
  });
  channel->onError([weak](std::string error) {
    if (auto self = weak.lock())
      self->fail("webrtc_error: " + error);
  });
}

void WebRtcConnectTransport::start() {
  std::weak_ptr<WebRtcConnectTransport> weak = weak_from_this();
  m_peer->onLocalDescription([weak](rtc::Description description) {
    if (auto self = weak.lock()) {
      Json signal = {{"kind", "description"}, {"type", description.typeString()}, {"sdp", std::string(description)}};
      self->emitSignal(signal.dump());
    }
  });
  m_peer->onLocalCandidate([weak](rtc::Candidate candidate) {
    if (auto self = weak.lock()) {
      Json signal = {{"kind", "candidate"}, {"candidate", std::string(candidate)}, {"mid", candidate.mid()}};
      self->emitSignal(signal.dump());
    }
  });
  m_peer->onStateChange([weak](rtc::PeerConnection::State state) {
    auto self = weak.lock();
    if (!self)
      return;
    // Disconnected can heal by itself; the session's keepalive decides when to give up.
    if (state == rtc::PeerConnection::State::Failed)
      self->fail("webrtc_failed");
    else if (state == rtc::PeerConnection::State::Closed)
      self->fail("webrtc_closed");
  });
  if (m_offerer) {
    rtc::DataChannelInit init;
    init.reliability.unordered = false;
    auto created = m_peer->createDataChannel(m_label, init);
    {
      std::lock_guard lock(m_mutex);
      m_channel = created;
    }
    wireChannel(created);
  } else {
    m_peer->onDataChannel([weak](std::shared_ptr<rtc::DataChannel> incoming) {
      auto self = weak.lock();
      if (!self || (self->m_strictLabel && incoming->label() != self->m_label))
        return;
      {
        std::lock_guard lock(self->m_mutex);
        if (self->m_channel)
          return;
        self->m_channel = incoming;
      }
      self->wireChannel(incoming);
      if (incoming->isOpen())
        self->emitOpened();
    });
  }
}

void WebRtcConnectTransport::applySignal(const Json &data) {
  const std::string kind = jsonString(data, "kind", 32);
  try {
    if (kind == "description") {
      const std::string type = jsonString(data, "type", 16);
      // Offerer accepts only answers and vice versa; anything else is noise.
      if ((m_offerer && type != "answer") || (!m_offerer && type != "offer"))
        return;
      m_peer->setRemoteDescription(rtc::Description(jsonString(data, "sdp", 64 * 1024), type));
      std::vector<Json> pending;
      {
        std::lock_guard lock(m_mutex);
        m_haveRemoteDescription = true;
        pending.swap(m_pendingCandidates);
      }
      for (const Json &candidate : pending)
        applySignal(candidate);
    } else if (kind == "candidate") {
      {
        std::lock_guard lock(m_mutex);
        if (!m_haveRemoteDescription) {
          if (m_pendingCandidates.size() < 64)
            m_pendingCandidates.push_back(data);
          return;
        }
      }
      m_peer->addRemoteCandidate(rtc::Candidate(jsonString(data, "candidate", 1024), jsonString(data, "mid", 64)));
    }
  } catch (const std::exception &error) {
    fail(std::string("webrtc_signal: ") + error.what());
  }
}

bool WebRtcConnectTransport::isOpen() const {
  auto current = channel();
  return current && current->isOpen();
}

bool WebRtcConnectTransport::sendText(const std::string &text) {
  auto current = channel();
  try {
    if (!current || !current->isOpen())
      return false;
    current->send(text);
    return true;
  } catch (const std::exception &) {
    return false;
  }
}

bool WebRtcConnectTransport::sendBinary(const std::string &bytes) {
  auto current = channel();
  try {
    if (!current || !current->isOpen())
      return false;
    current->send(reinterpret_cast<const std::byte *>(bytes.data()), bytes.size());
    return true;
  } catch (const std::exception &) {
    return false;
  }
}

std::size_t WebRtcConnectTransport::bufferedAmount() const {
  auto current = channel();
  return current ? current->bufferedAmount() : 0;
}

void WebRtcConnectTransport::close() {
  unbind();
  if (auto current = channel())
    current->close();
  if (m_peer)
    m_peer->close();
}

} // namespace orchard::connect
