/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/Layout.hpp"
#include "network/Http.hpp"
#include "update/Manifest.hpp"

#include <memory>
#include <optional>
#include <string>

namespace orchard::boot {

// Release paths are relative to the depot root.
inline constexpr const char *kDefaultServer = "https://depot.sfg545.dev/";

// Fetches release metadata and returns it only after the signature, hash
// chain, channel name, version and platform all check out.
class ReleaseClient {
public:
  ReleaseClient(std::string server, std::string platform);

  ChannelRelease fetchChannel(const std::string &channel, const CancelToken &cancel);

  struct SignedManifest {
    Manifest manifest;
    std::string envelope; // kept on disk for repair and rollback
  };
  // Through the channel file, which pins the manifest hash.
  SignedManifest fetchManifest(const ChannelRelease &release, const CancelToken &cancel);
  // By name, for repairing a version the channel has moved past.
  SignedManifest fetchManifest(const std::string &version, const CancelToken &cancel);

  std::string objectsUrl() const { return server_ + "objects/"; }
  HttpSession &session() { return *session_; }

private:
  std::string server_;
  std::string platform_;
  std::unique_ptr<HttpSession> session_;
};

void saveManifest(const Layout &layout, const std::string &platform, const std::string &envelope,
                  const Manifest &manifest);
// Re-verifies the stored signature; nullopt when missing or invalid.
std::optional<Manifest> loadManifest(const Layout &layout, const std::string &platform, const std::string &version);

} // namespace orchard::boot
