/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/Layout.hpp"
#include "util/Json.hpp"

#include <cstdint>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace orchard::boot {

// install.json. Unknown keys survive a rewrite so an older bootstrapper never
// drops state a newer one added.
//
//   {"schema":1, "channel":"stable", "platform":"win-x86_64",
//    "current":"4.2.14", "previous":"4.2.13", "pending":"4.2.15",
//    "components":{"qt":"6.8.3-1", ...},              components of "current"
//    "versions":{"4.2.14":{"components":{...}, "launch":{...},
//                          "healthy":true, "launchAttempts":0}},
//    "installedComponents":{"qt":{"6.8.3-1":"<digest>"}},
//    "skippedVersions":["4.2.12"], "channelSequence":{"stable":42},
//    "lastCheck":1790000000, "autoUpdate":true,
//    "bootstrapperVersion":"1.0.0", "created":["<shortcut path>", ...]}
class InstallState {
public:
  static std::optional<InstallState> load(const Layout &layout);
  static InstallState fresh(const std::string &channel, const std::string &platform);
  // Load (or start fresh), apply `change` and save, all under the state lock.
  static InstallState modify(const Layout &layout, const std::function<void(InstallState &)> &change);
  void save(const Layout &layout) const;

  const Json &doc() const { return doc_; }

  std::string get(const char *key) const { return optionalString(doc_, key); }
  void set(const char *key, const std::string &value);

  std::string channel() const { return get("channel"); }
  std::string platform() const { return get("platform"); }
  std::string current() const { return get("current"); }
  std::string previous() const { return get("previous"); }
  std::string pending() const { return get("pending"); }

  struct VersionInfo {
    std::map<std::string, std::string> components;
    Json launch;
    std::string icon; // relative to the version directory
    bool healthy = false;
    std::uint64_t launchAttempts = 0;
  };
  std::optional<VersionInfo> version(const std::string &version) const;
  void putVersion(const std::string &version, const VersionInfo &info);
  void eraseVersion(const std::string &version);
  std::vector<std::string> versions() const;
  void setHealth(const std::string &version, bool healthy, std::uint64_t attempts);

  // Switches "current", keeping the outgoing version as "previous".
  void activate(const std::string &version);

  std::string componentDigest(const std::string &name, const std::string &version) const;
  void recordComponent(const std::string &name, const std::string &version, const std::string &digest);
  void forgetComponent(const std::string &name, const std::string &version);
  std::map<std::string, std::vector<std::string>> installedComponents() const;

  bool skipped(const std::string &version) const;
  void skip(const std::string &version);

  std::uint64_t sequence(const std::string &channel) const;
  void setSequence(const std::string &channel, std::uint64_t sequence);

  std::uint64_t number(const char *key, std::uint64_t fallback = 0) const {
    return optionalUint(doc_, key, fallback);
  }
  void setNumber(const char *key, std::uint64_t value) { doc_[key] = value; }
  bool flag(const char *key, bool fallback) const { return optionalBool(doc_, key, fallback); }
  void setFlag(const char *key, bool value) { doc_[key] = value; }

  std::vector<std::string> created() const;
  void addCreated(const std::vector<std::string> &entries);

private:
  explicit InstallState(Json doc) : doc_(std::move(doc)) {}
  Json doc_;
};

} // namespace orchard::boot
