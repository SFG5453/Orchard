/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/InstallState.hpp"
#include "install/Layout.hpp"
#include "storage/ObjectStore.hpp"
#include "update/Manifest.hpp"

#include <cstdint>
#include <vector>

namespace orchard::boot {

struct StagePlan {
  std::vector<const Component *> components; // missing locally; unchanged ones cost nothing
  bool buildApp = false;
  std::vector<Chunk> objects;     // still to obtain
  std::uint64_t downloadBytes = 0;
  std::uint64_t buildBytes = 0;   // bytes written while assembling
  bool empty() const { return components.empty() && !buildApp; }
};

// Builds a release next to the running one: every directory is assembled
// under a ".<name>.staging" sibling, verified, then renamed into place.
class Stager {
public:
  Stager(const Layout &layout, const InstallState &state, std::string platform);

  StagePlan plan(const Manifest &manifest, const ObjectStore &store) const;
  // Pulls needed objects out of installed files (any version whose manifest is
  // on disk) so only genuinely new bytes are downloaded.
  void reuseInstalled(StagePlan &plan, ObjectStore &store, const CancelToken &cancel) const;
  void build(const Manifest &manifest, const StagePlan &plan, const ObjectStore &store,
             const CancelToken &cancel) const;

private:
  void buildTree(const std::vector<FileEntry> &files, const std::filesystem::path &final, const ObjectStore &store,
                 const CancelToken &cancel) const;

  const Layout &layout_;
  const InstallState &state_;
  std::string platform_;
};

void requireDiskSpace(const std::filesystem::path &root, std::uint64_t bytes);

} // namespace orchard::boot
