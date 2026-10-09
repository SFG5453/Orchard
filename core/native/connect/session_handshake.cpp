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

// Session establishment: hello, challenge and auth in cleartext, then sealed
// capabilities, roles and the one-time initial playback resolution.

#include "connect/session.h"

namespace orchard::connect {

void Session::sendHello() {
  m_nonceController = randomBytes(kNonceBytes);
  Json hello = {{"type", msg::Hello},
                {"session_id", id()},
                {"device_id", m_owner.selfDevice().id},
                {"target_id", peer().id},
                {"nonce", base64Encode(m_nonceController)},
                {"resume", m_resolved}};
  stampProtocol(hello);
  m_handshakeSentAtMs = monotonicMs();
  sendClear(hello);
}

void Session::handleReject(const Json &message) {
  std::string reason = jsonString(message, "code", 64);
  if (reason.empty())
    reason = code::Unauthorized;
  finish(reason);
  m_owner.sessionFinished(*this, reason);
}

void Session::handleChallenge(const Json &message) {
  if (m_state != SessionState::Authenticating)
    return;
  if (std::string error = checkProtocol(message); !error.empty()) {
    finish(error);
    m_owner.sessionFinished(*this, error);
    return;
  }
  const DeviceInfo &self = m_owner.selfDevice();
  const auto nonce = base64Decode(jsonString(message, "nonce", 128));
  const auto proof = base64Decode(jsonString(message, "proof", 128));
  if (!nonce || nonce->size() != kNonceBytes || !proof || jsonString(message, "device_id", 64) != peer().id ||
      !equalSecret(*proof, handshakeProof(m_grant.key, "target", id(), m_nonceController, *nonce, self.id, peer().id))) {
    finish(code::Unauthorized);
    m_owner.sessionFinished(*this, code::Unauthorized);
    return;
  }
  m_rttMs = monotonicMs() - m_handshakeSentAtMs;
  m_nonceTarget = *nonce;
  sendClear({{"type", msg::Auth},
             {"proof", base64Encode(handshakeProof(m_grant.key, "controller", id(), m_nonceController, m_nonceTarget,
                                                   self.id, peer().id))}});
  if (!m_channel.establish(m_grant.key, m_nonceController, m_nonceTarget, true)) {
    dropTransport();
    m_owner.linkLost(*this, "key_derivation_failed");
    return;
  }
  setState(SessionState::NegotiatingCapabilities);
  sendCapabilities();
}

void Session::handleAuth(const Json &message) {
  if (m_state != SessionState::Authenticating || m_channel.ready())
    return;
  const auto proof = base64Decode(jsonString(message, "proof", 128));
  const DeviceInfo &self = m_owner.selfDevice();
  if (!proof || !equalSecret(*proof, handshakeProof(m_grant.key, "controller", id(), m_nonceController,
                                                    m_nonceTarget, peer().id, self.id))) {
    sendClear({{"type", msg::Reject}, {"code", code::Unauthorized}});
    dropTransport();
    m_owner.linkLost(*this, code::Unauthorized);
    return;
  }
  m_rttMs = monotonicMs() - m_handshakeSentAtMs;
  if (!m_channel.establish(m_grant.key, m_nonceController, m_nonceTarget, false)) {
    dropTransport();
    m_owner.linkLost(*this, "key_derivation_failed");
    return;
  }
  setState(SessionState::NegotiatingCapabilities);
}

void Session::sendCapabilities() {
  // The controller's snapshot carries its whole queue so a transfer keeps it.
  sendSealed({{"type", msg::Capabilities},
              {"device", toJson(m_owner.selfDevice())},
              {"playback", toJson(m_owner.localSnapshot())}});
}

void Session::sendDeviceUpdate() {
  if (m_state == SessionState::Connected)
    sendSealed({{"type", msg::Capabilities}, {"device", toJson(m_owner.selfDevice())}});
}

void Session::handleCapabilities(const Json &message) {
  DeviceInfo device = deviceFromJson(message.value("device", Json::object()));
  if (m_state == SessionState::Connected) {
    if (device.id == peer().id && device.protocolMajor == kProtocolMajor) {
      m_grant.peer = std::move(device);
      m_owner.sessionEvent(*this, "peer", Json::object());
    }
    return;
  }
  if (m_state != SessionState::NegotiatingCapabilities)
    return;
  if (device.id != peer().id || device.protocolMajor != kProtocolMajor) {
    finish(code::IncompatibleProtocol);
    m_owner.sessionFinished(*this, code::IncompatibleProtocol);
    return;
  }
  m_grant.peer = std::move(device);
  m_peerSnapshot = snapshotFromJson(message.value("playback", Json::object()), kCommandQueueLimit);
  m_peerSnapshotAtMs = monotonicMs();
  m_haveCapabilities = true;
  m_owner.sessionEvent(*this, "peer", Json::object());

  if (role() == SessionRole::Controller) {
    m_owner.sessionEvent(*this, "remote_state", toJson(m_peerSnapshot));
    if (m_resolved)
      becomeConnected();
    else
      setState(SessionState::ResolvingInitialPlayback);
    return;
  }

  sendCapabilities();
  sendRoles(m_owner.rolesFor(*this));
  if (m_resolved) {
    // A reconnect: the target kept playing and simply carries on.
    becomeConnected();
    sendState(m_owner.localSnapshot());
    return;
  }
  setState(SessionState::ResolvingInitialPlayback);
  const PlaybackSnapshot local = m_owner.localSnapshot();
  const InitialOutcome outcome = resolveInitialPlayback(local, m_peerSnapshot);
  m_resolved = true;
  sendSealed({{"type", msg::InitialPlayback}, {"outcome", outcomeName(outcome)}});
  Json event = {{"outcome", outcomeName(outcome)}};
  if (outcome == InitialOutcome::Transfer) {
    PlaybackSnapshot transfer = m_peerSnapshot;
    transfer.position = projectPosition(transfer, monotonicMs() - m_peerSnapshotAtMs + m_rttMs / 2);
    event["snapshot"] = toJson(transfer);
  }
  m_owner.sessionEvent(*this, "initial_playback", std::move(event));
  becomeConnected();
  sendState(m_owner.localSnapshot());
}

void Session::handleRoles(const Json &message) {
  m_roles = rolesFromJson(message.value("roles", Json::object()));
  m_owner.sessionEvent(*this, "roles", toJson(m_roles));
}

void Session::handleInitialPlayback(const Json &message) {
  if (m_state != SessionState::ResolvingInitialPlayback || m_resolved)
    return;
  std::string outcome = jsonString(message, "outcome", 32);
  if (outcome != "target_wins" && outcome != "transferred")
    outcome = "none";
  m_resolved = true;
  m_owner.sessionEvent(*this, "initial_playback", {{"outcome", outcome}});
  becomeConnected();
}

void Session::becomeConnected() {
  if (auto loop = m_loop.lock())
    loop->cancel(m_handshakeTimer);
  m_handshakeTimer = 0;
  // Resolution never repeats, even if a later reconnect replays the handshake.
  m_resolved = true;
  setState(SessionState::Connected);
  armKeepalive();
}

} // namespace orchard::connect
