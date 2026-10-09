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

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace orchard::connect {

// What a device is and can do. Feature flags live here, never in the version.
struct DeviceInfo {
  std::string id; // Orchard account device id
  std::string name;
  std::string platform; // linux, windows, macos, android, ios
  std::string kind;     // desktop, mobile or tablet
  int protocolMajor = kProtocolMajor;
  int protocolMinor = kProtocolMinor;
  bool canRenderAudio = true;
  bool canMixAudio = false;
  bool canFetchArtwork = false;
  std::vector<std::string> providers;
  std::map<std::string, bool> providerSessions;
  std::vector<std::string> audioTransports;
  Json currentlyPlaying; // short track summary or null
  Json extra;            // unknown capability fields, kept for forward compatibility

  [[nodiscard]] bool isDesktop() const { return kind == "desktop"; }
  [[nodiscard]] bool hasProviderSession(const std::string &provider) const;
};

Json toJson(const DeviceInfo &device);
DeviceInfo deviceFromJson(const Json &json);

struct LanEndpoint {
  std::string host;
  std::uint16_t port = 0;
};

Json toJson(const std::vector<LanEndpoint> &endpoints);
std::vector<LanEndpoint> endpointsFromJson(const Json &json);

struct NetworkInterface {
  std::string name;
  std::string address; // IPv4 dotted quad
};

// Routable LAN addresses first: physical before virtual, home ranges before Docker's 172.16/12.
std::vector<std::string> rankLanAddresses(const std::vector<NetworkInterface> &interfaces);

// Roles for one target and the controllers attached to it. Ids refer to DeviceInfo::id.
struct Roles {
  std::string target;
  std::vector<std::string> controllers;
  std::string mixHost;
  std::string artworkHost;
  std::map<std::string, std::string> providerHosts; // provider -> device; absent means unavailable

  bool operator==(const Roles &other) const;
  bool operator!=(const Roles &other) const { return !(*this == other); }
};

Json toJson(const Roles &roles);
Roles rolesFromJson(const Json &json);

// The target owns playback; a connected desktop owns expensive work; providers
// run where their sessions live, preferring the target.
Roles selectRoles(const DeviceInfo &target, const std::vector<DeviceInfo> &controllers);

} // namespace orchard::connect
