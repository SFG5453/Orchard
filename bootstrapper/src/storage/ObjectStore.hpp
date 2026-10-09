/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "update/Manifest.hpp"

#include <cstdint>
#include <filesystem>
#include <string>
#include <string_view>

namespace orchard::boot {

// Verified objects under cache/objects/<aa>/<sha256>. Downloads land in
// "<sha256>.part" and are renamed only after their hash matches, so anything
// without the suffix has been checked.
class ObjectStore {
public:
  explicit ObjectStore(std::filesystem::path dir);

  const std::filesystem::path &dir() const { return dir_; }
  std::filesystem::path objectPath(std::string_view sha256) const;
  std::filesystem::path partPath(std::string_view sha256) const;
  bool contains(std::string_view sha256) const;
  void commitPart(std::string_view sha256);

  // Copies [offset, offset + size) of an installed file in when it hashes to
  // `sha256`. Lets updates and repairs reuse bytes already on disk.
  bool importRange(const Chunk &chunk, const std::filesystem::path &source, std::uint64_t offset);

  void clear();

private:
  std::filesystem::path dir_;
};

// Writes `entry` to `target` from stored objects, then checks the whole-file
// size and hash. Throws and removes `target` on any mismatch.
void assembleFile(const FileEntry &entry, const std::filesystem::path &target, const ObjectStore &store);

// Checks an installed file against its manifest entry.
bool fileMatches(const FileEntry &entry, const std::filesystem::path &path);

} // namespace orchard::boot
