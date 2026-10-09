/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "install/Repair.hpp"

#include "install/InstallState.hpp"
#include "install/Stager.hpp"
#include "network/Downloader.hpp"
#include "platform/Platform.hpp"
#include "update/ReleaseClient.hpp"
#include "util/Log.hpp"

#include <algorithm>
#include <atomic>
#include <thread>

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

struct Target {
  const FileEntry *entry;
  fs::path path;
};

std::vector<Target> damagedFiles(const std::vector<Target> &targets, UpdateReporter &reporter,
                                 const CancelToken &cancel) {
  std::uint64_t total = 0;
  for (const Target &target : targets)
    total += target.entry->size;
  std::atomic<std::size_t> next{0};
  std::atomic<std::uint64_t> hashed{0};
  std::vector<char> bad(targets.size(), 0);
  std::vector<std::thread> workers;
  const unsigned count = std::clamp(std::thread::hardware_concurrency(), 2u, 8u);
  std::atomic<unsigned> active{count};
  for (unsigned i = 0; i < count; ++i) {
    workers.emplace_back([&] {
      for (std::size_t index; !cancel.cancelled() && (index = next++) < targets.size();) {
        bad[index] = !fileMatches(*targets[index].entry, targets[index].path);
        hashed += targets[index].entry->size;
      }
      --active;
    });
  }
  while (active > 0) {
    reporter.progress(hashed, total);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
  for (std::thread &worker : workers)
    worker.join();
  cancel.throwIfCancelled();
  std::vector<Target> out;
  for (std::size_t i = 0; i < targets.size(); ++i) {
    if (bad[i])
      out.push_back(targets[i]);
  }
  return out;
}

} // namespace

RepairResult repair(const Layout &layout, const std::string &server, const std::string &platform,
                    UpdateReporter &reporter, CancelToken &cancel) {
  const auto work = platform::FileLock::tryAcquire(layout.workLock());
  if (!work)
    throw Error("another Orchard update or repair is already running");
  const std::optional<InstallState> state = InstallState::load(layout);
  if (!state || state->current().empty())
    throw Error("Orchard is not installed in " + toUtf8(layout.root));
  const std::string version = state->current();
  reporter.bindCancel(cancel);

  reporter.set("staging", "Reading release manifest");
  ReleaseClient client(server, platform);
  std::optional<Manifest> manifest = loadManifest(layout, platform, version);
  if (!manifest) {
    auto fetched = client.fetchManifest(version, cancel);
    saveManifest(layout, platform, fetched.envelope, fetched.manifest);
    manifest = std::move(fetched.manifest);
  }

  std::vector<Target> targets;
  for (const FileEntry &file : manifest->files)
    targets.push_back({&file, layout.versionDir(version) / safeRelativePath(file.path)});
  for (const Component &component : manifest->components) {
    for (const FileEntry &file : component.files)
      targets.push_back({&file, layout.componentDir(component.name, component.version) / safeRelativePath(file.path)});
  }
  reporter.set("staging", "Checking files");
  const std::vector<Target> damaged = damagedFiles(targets, reporter, cancel);
  RepairResult result{targets.size(), damaged.size(), 0};
  log::info("repair: " + std::to_string(damaged.size()) + " of " + std::to_string(targets.size()) +
            " files need rebuilding");

  if (!damaged.empty()) {
    ObjectStore store(layout.objectsDir());
    StagePlan plan;
    for (const Target &target : damaged) {
      for (const Chunk &chunk : target.entry->chunks) {
        if (!store.contains(chunk.sha256)) {
          plan.objects.push_back(chunk);
          plan.downloadBytes += chunk.size;
        }
      }
    }
    // The index includes the damaged files themselves; importRange rehashes,
    // so only their corrupt chunks fall through to the download.
    Stager(layout, *state, platform).reuseInstalled(plan, store, cancel);
    requireDiskSpace(layout.root, plan.downloadBytes);
    reporter.set("downloading", "Downloading missing files");
    Downloader(client.objectsUrl(), store).fetch(plan.objects, cancel, [&](std::uint64_t done, std::uint64_t total) {
      reporter.progress(done, total);
    });
    result.downloaded = plan.downloadBytes;

    reporter.set("staging", "Repairing files");
    for (const Target &target : damaged) {
      fs::path temp = target.path;
      temp += ".orchard-repair";
      ensureInside(layout.root, temp);
      assembleFile(*target.entry, temp, store);
      std::error_code error;
      fs::rename(temp, target.path, error);
      if (error) {
        fs::remove(temp, error);
        throw Error("cannot replace " + toUtf8(target.path) + " (close Orchard and try again)");
      }
    }
    store.clear();
  }

  InstallState::modify(layout, [&](InstallState &s) {
    for (const Component &component : manifest->components)
      s.recordComponent(component.name, component.version, component.digest);
    if (!s.version(version)) {
      InstallState::VersionInfo info;
      for (const Component &component : manifest->components)
        info.components[component.name] = component.version;
      info.launch = manifest->launch.raw;
      info.icon = manifest->icon;
      info.healthy = true;
      s.putVersion(version, info);
    }
  });
  reporter.set("idle");
  return result;
}

} // namespace orchard::boot
