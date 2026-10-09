/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "util/Json.hpp"

#include <cstdint>
#include <map>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace orchard::boot {

// Highest manifest/channel schema this bootstrapper understands.
inline constexpr int kSchema = 1;

// One content-addressed object, stored at /objects/<sha256>.
struct Chunk {
  std::string sha256;
  std::uint64_t size = 0;
};

// A file is the concatenation of its chunks. A whole-file object is one chunk
// whose hash equals the file hash.
struct FileEntry {
  std::string path; // validated, '/'-separated, relative
  std::uint64_t size = 0;
  std::string sha256;
  bool executable = false;
  std::vector<Chunk> chunks;
};

struct Component {
  std::string name;
  std::string version;
  std::vector<FileEntry> files;
  // Stable digest of the file list; catches a component changed without a version bump.
  std::string digest;
};

struct LaunchSpec {
  std::string executable; // relative to the version directory
  std::vector<std::string> args;
  std::vector<std::pair<std::string, std::string>> env;
  std::vector<std::pair<std::string, std::vector<std::string>>> prependPaths;
  Json raw; // kept verbatim in install.json so launch never reparses the manifest
};

struct Manifest {
  std::string version;
  std::string platform;
  std::string minimumBootstrapper;
  std::vector<FileEntry> files;
  std::vector<Component> components;
  LaunchSpec launch;
  std::string icon; // optional, relative to the version directory

  const Component *component(std::string_view name) const;
};

struct PlatformRelease {
  std::string manifest; // path relative to the server base
  std::string sha256;   // hash of the signed manifest envelope
  std::uint64_t size = 0;
};

struct ChannelRelease {
  std::string channel;
  std::uint64_t sequence = 0; // increases with every publish; blocks replayed old files
  std::string version;
  std::string minimumBootstrapper;
  std::map<std::string, PlatformRelease> platforms;
  std::string bootstrapperVersion;
  std::map<std::string, FileEntry> bootstrappers; // by platform
};

FileEntry parseFileEntry(const Json &value);
Manifest parseManifest(std::string_view payload, std::string_view expectedVersion,
                       std::string_view expectedPlatform);
ChannelRelease parseChannel(std::string_view payload, std::string_view expectedChannel);
LaunchSpec parseLaunch(const Json &value);

std::string componentDigest(const std::vector<FileEntry> &files);
std::uint64_t totalSize(const std::vector<FileEntry> &files);

} // namespace orchard::boot
