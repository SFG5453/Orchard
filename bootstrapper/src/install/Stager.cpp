/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "install/Stager.hpp"

#include "update/ReleaseClient.hpp"
#include "util/Log.hpp"

#include <map>
#include <set>

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

struct Location {
  fs::path file;
  std::uint64_t offset;
};

void indexFiles(const std::vector<FileEntry> &files, const fs::path &dir, std::map<std::string, Location> &index) {
  for (const FileEntry &file : files) {
    std::uint64_t offset = 0;
    for (const Chunk &chunk : file.chunks) {
      index.try_emplace(chunk.sha256, Location{dir / safeRelativePath(file.path), offset});
      offset += chunk.size;
    }
  }
}

} // namespace

Stager::Stager(const Layout &layout, const InstallState &state, std::string platform)
    : layout_(layout), state_(state), platform_(std::move(platform)) {}

StagePlan Stager::plan(const Manifest &manifest, const ObjectStore &store) const {
  StagePlan plan;
  std::set<std::string> seen;
  const auto want = [&](const std::vector<FileEntry> &files) {
    for (const FileEntry &file : files) {
      plan.buildBytes += file.size;
      for (const Chunk &chunk : file.chunks) {
        if (seen.insert(chunk.sha256).second && !store.contains(chunk.sha256)) {
          plan.objects.push_back(chunk);
          plan.downloadBytes += chunk.size;
        }
      }
    }
  };
  for (const Component &component : manifest.components) {
    const std::string recorded = state_.componentDigest(component.name, component.version);
    std::error_code error;
    const bool present = fs::is_directory(layout_.componentDir(component.name, component.version), error);
    if (!recorded.empty() && recorded != component.digest)
      throw Error("component " + component.name + " " + component.version +
                  " changed without a version bump; the release is inconsistent");
    if (present && !recorded.empty())
      continue;
    plan.components.push_back(&component);
    want(component.files);
  }
  std::error_code error;
  plan.buildApp = !state_.version(manifest.version) || !fs::is_directory(layout_.versionDir(manifest.version), error);
  if (plan.buildApp)
    want(manifest.files);
  return plan;
}

void Stager::reuseInstalled(StagePlan &plan, ObjectStore &store, const CancelToken &cancel) const {
  if (plan.objects.empty())
    return;
  std::map<std::string, Location> index;
  for (const std::string &version : state_.versions()) {
    const auto manifest = loadManifest(layout_, platform_, version);
    if (!manifest)
      continue;
    indexFiles(manifest->files, layout_.versionDir(version), index);
    for (const Component &component : manifest->components) {
      if (!state_.componentDigest(component.name, component.version).empty())
        indexFiles(component.files, layout_.componentDir(component.name, component.version), index);
    }
  }
  std::vector<Chunk> remaining;
  std::uint64_t reused = 0;
  for (const Chunk &chunk : plan.objects) {
    cancel.throwIfCancelled();
    const auto hit = index.find(chunk.sha256);
    if (hit != index.end() && store.importRange(chunk, hit->second.file, hit->second.offset)) {
      reused += chunk.size;
      continue;
    }
    remaining.push_back(chunk);
  }
  if (reused > 0)
    log::info("reused " + std::to_string(reused) + " bytes from installed files");
  plan.objects = std::move(remaining);
  plan.downloadBytes -= reused;
}

void Stager::buildTree(const std::vector<FileEntry> &files, const fs::path &final, const ObjectStore &store,
                       const CancelToken &cancel) const {
  const fs::path staging = Layout::staging(final);
  removeTree(staging);
  fs::create_directories(staging);
  for (const FileEntry &file : files) {
    cancel.throwIfCancelled();
    const fs::path target = staging / safeRelativePath(file.path);
    ensureInside(layout_.root, target);
    assembleFile(file, target, store);
  }
  // A directory at `final` without a state record is debris from an
  // interrupted run; nothing references it.
  removeTree(final);
  fs::rename(staging, final);
}

void Stager::build(const Manifest &manifest, const StagePlan &plan, const ObjectStore &store,
                   const CancelToken &cancel) const {
  for (const Component *component : plan.components)
    buildTree(component->files, layout_.componentDir(component->name, component->version), store, cancel);
  if (plan.buildApp)
    buildTree(manifest.files, layout_.versionDir(manifest.version), store, cancel);
}

void requireDiskSpace(const fs::path &root, std::uint64_t bytes) {
  std::error_code error;
  fs::path probe = root;
  while (!fs::exists(probe, error) && probe.has_parent_path() && probe != probe.parent_path())
    probe = probe.parent_path();
  const fs::space_info space = fs::space(probe, error);
  // Keep 64 MiB spare so Orchard itself still has room to breathe.
  if (!error && space.available < bytes + (64ull << 20))
    throw Error("not enough disk space: " + std::to_string((bytes >> 20) + 64) + " MB needed, " +
                std::to_string(space.available >> 20) + " MB free");
}

} // namespace orchard::boot
