/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "util/Files.hpp"

#include <filesystem>
#include <string>

namespace orchard::boot {

// Paths inside an install root:
//   orchard[.exe]                 bootstrapper
//   install.json                  install state
//   update-state.json             download progress, read by the app
//   versions/<v>/                 app files per release
//   components/<name>/<v>/        shared runtimes (Qt, ONNX Runtime, models, ...)
//   manifests/<v>-<platform>.json signed manifests of installed versions
//   cache/objects/                verified download cache
//   logs/
struct Layout {
  std::filesystem::path root;

  std::filesystem::path installJson() const { return root / "install.json"; }
  std::filesystem::path updateState() const { return root / "update-state.json"; }
  std::filesystem::path bootstrapper() const;
  std::filesystem::path versionsDir() const { return root / "versions"; }
  std::filesystem::path versionDir(const std::string &version) const { return versionsDir() / fromUtf8(version); }
  std::filesystem::path componentsDir() const { return root / "components"; }
  std::filesystem::path componentDir(const std::string &name, const std::string &version) const {
    return componentsDir() / fromUtf8(name) / fromUtf8(version);
  }
  std::filesystem::path manifestFile(const std::string &version, const std::string &platform) const {
    return root / "manifests" / fromUtf8(version + "-" + platform + ".json");
  }
  std::filesystem::path objectsDir() const { return root / "cache" / "objects"; }
  std::filesystem::path cancelFlag() const { return root / "cache" / "cancel"; }
  std::filesystem::path logFile() const { return root / "logs" / "bootstrapper.log"; }
  // Held while install.json is read-modify-written (milliseconds).
  std::filesystem::path stateLock() const { return root / ".state.lock"; }
  // Held for a whole download/stage/repair (minutes).
  std::filesystem::path workLock() const { return root / ".work.lock"; }

  // Staging names start with '.' so they never collide with a real version.
  static std::filesystem::path staging(const std::filesystem::path &final);
};

} // namespace orchard::boot
