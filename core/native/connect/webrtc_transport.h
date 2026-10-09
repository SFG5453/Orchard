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

#include "connect/protocol.h"
#include "connect/transport.h"

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace rtc {
class PeerConnection;
class DataChannel;
struct IceServer;
} // namespace rtc

namespace orchard::connect {

// One ordered, reliable data channel. Audio rides it as Orchard frames, outside WebRTC's
// media pipeline, so nothing resamples or conceals it like a voice call.
class WebRtcConnectTransport final : public ConnectTransport,
                                     public std::enable_shared_from_this<WebRtcConnectTransport> {
public:
  // `iceServers` uses the browser shape: [{urls, username?, credential?}].
  // `strictLabel` false accepts an inbound channel with any label (Listening Parties).
  WebRtcConnectTransport(const Json &iceServers, bool offerer, std::string label = kDataChannelLabel,
                         bool strictLabel = true);
  ~WebRtcConnectTransport() override;

  // bind() first. The offerer opens the channel, which starts negotiation.
  void start();
  // {kind: description, type, sdp} or {kind: candidate, candidate, mid}.
  void applySignal(const Json &data);

  [[nodiscard]] const char *kind() const override { return "webrtc"; }
  [[nodiscard]] bool isOpen() const override;
  bool sendText(const std::string &text) override;
  bool sendBinary(const std::string &bytes) override;
  [[nodiscard]] std::size_t bufferedAmount() const override;
  void close() override;

private:
  void wireChannel(const std::shared_ptr<rtc::DataChannel> &channel);
  std::shared_ptr<rtc::DataChannel> channel() const;
  void fail(const std::string &reason);

  std::shared_ptr<rtc::PeerConnection> m_peer;
  mutable std::mutex m_mutex;
  std::shared_ptr<rtc::DataChannel> m_channel;
  std::vector<Json> m_pendingCandidates;
  bool m_haveRemoteDescription = false;
  bool m_offerer;
  std::string m_label;
  bool m_strictLabel;
  std::atomic<bool> m_closed{false};
};

// Browser-shaped ICE servers to libdatachannel's. TURN over TCP/TLS is skipped:
// libjuice relays over UDP only, and it takes at most two TURN servers.
std::vector<rtc::IceServer> parseIceServers(const Json &iceServers);

} // namespace orchard::connect
