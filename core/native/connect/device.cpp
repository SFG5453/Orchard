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

#include "connect/device.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdio>
#include <regex>
#include <set>

namespace orchard::connect {
namespace {

const std::set<std::string> kKnownDeviceKeys = {
    "id", "name", "platform", "kind", "connect_protocol_major", "connect_protocol_minor",
    "can_render_audio", "can_mix_audio", "can_fetch_artwork", "providers", "provider_sessions",
    "audio_transports", "currently_playing"};

std::vector<std::string> stringList(const Json &json, const char *key, std::size_t limit = 32) {
  std::vector<std::string> out;
  if (!json.is_object() || !json.contains(key) || !json[key].is_array())
    return out;
  for (const Json &item : json[key]) {
    if (out.size() >= limit)
      break;
    if (item.is_string() && !item.get<std::string>().empty() && item.get<std::string>().size() <= 64)
      out.push_back(item.get<std::string>());
  }
  return out;
}

std::string defaultKind(const std::string &platform) {
  if (platform == "android" || platform == "ios")
    return "mobile";
  if (platform == "linux" || platform == "windows" || platform == "macos")
    return "desktop";
  return "unknown";
}

std::string lowered(std::string text) {
  std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return std::tolower(c); });
  return text;
}

bool isVirtualAdapter(const std::string &name) {
  // Hyper-V, WSL, VPN and container adapters often enumerate before the real NIC.
  static const std::array<const char *, 21> patterns = {
      "vethernet", "hyper-v", "hyperv", "vmware", "vmnet", "virtualbox", "vboxnet",
      "wsl", "docker", "tailscale", "zerotier", "npcap", "loopback", "bluetooth",
      "tap-", "utun", "ppp", "hamachi", "nordlynx", "wireguard", "proton"};
  const std::string lower = lowered(name);
  if (std::any_of(patterns.begin(), patterns.end(), [&](const char *p) { return lower.find(p) != std::string::npos; }))
    return true;
  static const std::regex tun("tun[0-9]+|veth.*|br-.*|virbr[0-9]+");
  return std::regex_match(lower, tun);
}

int rangeScore(const std::string &address) {
  unsigned a = 0, b = 0;
  if (std::sscanf(address.c_str(), "%u.%u", &a, &b) != 2)
    return -1;
  if (a == 192 && b == 168)
    return 3;
  if (a == 10)
    return 2;
  if (a == 172 && b >= 16 && b <= 31)
    return 1;
  return 0;
}

} // namespace

bool DeviceInfo::hasProviderSession(const std::string &provider) const {
  const auto it = providerSessions.find(provider);
  return it != providerSessions.end() && it->second;
}

Json toJson(const DeviceInfo &device) {
  Json json = device.extra.is_object() ? device.extra : Json::object();
  json["id"] = device.id;
  json["name"] = device.name;
  json["platform"] = device.platform;
  json["kind"] = device.kind.empty() ? defaultKind(device.platform) : device.kind;
  json["connect_protocol_major"] = device.protocolMajor;
  json["connect_protocol_minor"] = device.protocolMinor;
  json["can_render_audio"] = device.canRenderAudio;
  json["can_mix_audio"] = device.canMixAudio;
  json["can_fetch_artwork"] = device.canFetchArtwork;
  json["providers"] = device.providers;
  Json sessions = Json::object();
  for (const auto &[provider, signedIn] : device.providerSessions)
    sessions[provider] = signedIn;
  json["provider_sessions"] = sessions;
  json["audio_transports"] = device.audioTransports;
  json["currently_playing"] = device.currentlyPlaying.is_object() ? device.currentlyPlaying : Json();
  return json;
}

DeviceInfo deviceFromJson(const Json &json) {
  DeviceInfo device;
  if (!json.is_object())
    return device;
  device.id = jsonString(json, "id", 64);
  device.name = jsonString(json, "name", 64);
  device.platform = jsonString(json, "platform", 16);
  device.kind = jsonString(json, "kind", 16);
  if (device.kind.empty())
    device.kind = defaultKind(device.platform);
  device.protocolMajor = static_cast<int>(jsonNumber(json, "connect_protocol_major", 0));
  device.protocolMinor = static_cast<int>(jsonNumber(json, "connect_protocol_minor", 0));
  device.canRenderAudio = jsonBool(json, "can_render_audio", false);
  device.canMixAudio = jsonBool(json, "can_mix_audio", false);
  device.canFetchArtwork = jsonBool(json, "can_fetch_artwork", false);
  device.providers = stringList(json, "providers");
  device.audioTransports = stringList(json, "audio_transports");
  if (json.contains("provider_sessions") && json["provider_sessions"].is_object()) {
    for (const auto &[provider, signedIn] : json["provider_sessions"].items()) {
      if (device.providerSessions.size() >= 32)
        break;
      if (signedIn.is_boolean() && provider.size() <= 64)
        device.providerSessions[provider] = signedIn.get<bool>();
    }
  }
  if (json.contains("currently_playing") && json["currently_playing"].is_object())
    device.currentlyPlaying = json["currently_playing"];
  device.extra = Json::object();
  for (const auto &[key, value] : json.items()) {
    if (!kKnownDeviceKeys.count(key) && key.size() <= 64)
      device.extra[key] = value;
  }
  return device;
}

Json toJson(const std::vector<LanEndpoint> &endpoints) {
  Json out = Json::array();
  for (const LanEndpoint &endpoint : endpoints)
    out.push_back({{"host", endpoint.host}, {"port", endpoint.port}});
  return out;
}

std::vector<LanEndpoint> endpointsFromJson(const Json &json) {
  std::vector<LanEndpoint> out;
  if (!json.is_array())
    return out;
  for (const Json &item : json) {
    if (out.size() >= 8)
      break;
    LanEndpoint endpoint{jsonString(item, "host", 64), static_cast<std::uint16_t>(jsonNumber(item, "port", 0))};
    // Literal IPv4 only: a hostname here would let presence data steer DNS lookups.
    unsigned a, b, c, d;
    char tail;
    if (endpoint.port != 0 &&
        std::sscanf(endpoint.host.c_str(), "%u.%u.%u.%u%c", &a, &b, &c, &d, &tail) == 4 && a < 256 && b < 256 &&
        c < 256 && d < 256)
      out.push_back(endpoint);
  }
  return out;
}

std::vector<std::string> rankLanAddresses(const std::vector<NetworkInterface> &interfaces) {
  struct Candidate {
    std::string address;
    bool isVirtual;
    int range;
  };
  std::vector<Candidate> candidates;
  for (const NetworkInterface &item : interfaces) {
    const int range = rangeScore(item.address);
    // Loopback, link-local (no DHCP lease) and anything unparsable stay out.
    if (range < 0 || item.address.rfind("127.", 0) == 0 || item.address.rfind("169.254.", 0) == 0)
      continue;
    candidates.push_back({item.address, isVirtualAdapter(item.name), range});
  }
  std::stable_sort(candidates.begin(), candidates.end(), [](const Candidate &left, const Candidate &right) {
    if (left.isVirtual != right.isVirtual)
      return !left.isVirtual;
    if (left.range != right.range)
      return left.range > right.range;
    return left.address < right.address;
  });
  std::vector<std::string> out;
  for (const Candidate &candidate : candidates) {
    if (std::find(out.begin(), out.end(), candidate.address) == out.end())
      out.push_back(candidate.address);
  }
  return out;
}

bool Roles::operator==(const Roles &other) const {
  return target == other.target && controllers == other.controllers && mixHost == other.mixHost &&
         artworkHost == other.artworkHost && providerHosts == other.providerHosts;
}

Json toJson(const Roles &roles) {
  Json providers = Json::object();
  for (const auto &[provider, host] : roles.providerHosts)
    providers[provider] = host;
  return {{"target", roles.target},
          {"controllers", roles.controllers},
          {"mix_host", roles.mixHost},
          {"artwork_host", roles.artworkHost},
          {"provider_hosts", providers}};
}

Roles rolesFromJson(const Json &json) {
  Roles roles;
  roles.target = jsonString(json, "target", 64);
  roles.controllers = stringList(json, "controllers", 16);
  roles.mixHost = jsonString(json, "mix_host", 64);
  roles.artworkHost = jsonString(json, "artwork_host", 64);
  if (json.is_object() && json.contains("provider_hosts") && json["provider_hosts"].is_object()) {
    for (const auto &[provider, host] : json["provider_hosts"].items()) {
      if (host.is_string() && roles.providerHosts.size() < 32)
        roles.providerHosts[provider] = host.get<std::string>();
    }
  }
  return roles;
}

Roles selectRoles(const DeviceInfo &target, const std::vector<DeviceInfo> &controllers) {
  Roles roles;
  roles.target = target.id;
  for (const DeviceInfo &controller : controllers)
    roles.controllers.push_back(controller.id);

  // Preference order for compute: the target when it is a desktop, then any desktop
  // controller, then whoever can do it, and finally the target itself.
  auto pick = [&](bool (*able)(const DeviceInfo &)) {
    if (target.isDesktop() && able(target))
      return target.id;
    for (const DeviceInfo &controller : controllers) {
      if (controller.isDesktop() && able(controller))
        return controller.id;
    }
    return target.id;
  };
  roles.mixHost = pick([](const DeviceInfo &d) { return d.canMixAudio; });
  roles.artworkHost = pick([](const DeviceInfo &d) { return d.canFetchArtwork; });

  std::set<std::string> providers(target.providers.begin(), target.providers.end());
  for (const DeviceInfo &controller : controllers)
    providers.insert(controller.providers.begin(), controller.providers.end());
  for (const std::string &provider : providers) {
    if (target.hasProviderSession(provider)) {
      roles.providerHosts[provider] = target.id;
      continue;
    }
    for (const DeviceInfo &controller : controllers) {
      if (controller.hasProviderSession(provider)) {
        roles.providerHosts[provider] = controller.id;
        break;
      }
    }
  }
  return roles;
}

} // namespace orchard::connect
