/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "update/UpdateManager.hpp"

#include "install/Stager.hpp"
#include "network/Downloader.hpp"
#include "platform/Platform.hpp"
#include "update/ReleaseClient.hpp"
#include "update/SelfUpdate.hpp"
#include "util/Log.hpp"
#include "util/Version.hpp"

#include <chrono>

namespace orchard::boot {

namespace {

std::uint64_t unixNow() {
  return static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count());
}

// A CDN serving yesterday's signed channel file must not roll clients back.
void requireFresh(const InstallState &state, const ChannelRelease &release) {
  if (release.sequence < state.sequence(release.channel))
    throw Error("release channel file is older than one already seen (sequence " +
                std::to_string(release.sequence) + " < " + std::to_string(state.sequence(release.channel)) + ")");
}

} // namespace

UpdateManager::UpdateManager(const Layout &layout, std::string server, std::string platform)
    : layout_(layout), server_(std::move(server)), platform_(std::move(platform)) {}

bool UpdateManager::wanted(const InstallState &state, const ChannelRelease &release) const {
  const std::string &latest = release.version;
  if (latest == state.current() || latest == state.pending() || state.skipped(latest))
    return false;
  if (state.current().empty())
    return true;
  // Downgrades only follow an explicit channel switch (canary -> stable).
  return versionLess(state.current(), latest) || state.flag("channelChanged", false);
}

void UpdateManager::requireBootstrapper(const std::string &minimum, const ChannelRelease &release,
                                        ReleaseClient &client, const CancelToken &cancel) {
  if (minimum.empty() || !versionLess(ORCHARD_BOOTSTRAPPER_VERSION, minimum))
    return;
  if (!release.bootstrapperVersion.empty() && !versionLess(release.bootstrapperVersion, minimum) &&
      updateBootstrapper(layout_, release, platform_, client, cancel))
    throw Relaunch();
  // The installed copy may already be new enough even though this one is not.
  throw TooOld("Orchard " + release.version + " needs installer " + minimum + " or newer (this is " +
               ORCHARD_BOOTSTRAPPER_VERSION + "). Download the latest Orchard installer.");
}

UpdateCheck UpdateManager::check(UpdateReporter &reporter, const CancelToken &cancel) {
  reporter.set("checking", "Checking for updates");
  const std::optional<InstallState> state = InstallState::load(layout_);
  if (!state)
    throw Error("Orchard is not installed in " + toUtf8(layout_.root));
  ReleaseClient client(server_, platform_);
  const ChannelRelease release = client.fetchChannel(state->channel(), cancel);
  requireFresh(*state, release);
  InstallState::modify(layout_, [&](InstallState &s) {
    s.setNumber("lastCheck", unixNow());
    s.setSequence(release.channel, release.sequence);
  });

  UpdateCheck result{state->channel(), state->current(), release.version, state->pending()};
  result.available = wanted(*state, release);
  if (result.available) {
    const auto signedManifest = client.fetchManifest(release, cancel);
    ObjectStore store(layout_.objectsDir());
    result.downloadBytes = Stager(layout_, *state, platform_).plan(signedManifest.manifest, store).downloadBytes;
  }
  reporter.setVersions(result.channel, result.current, result.latest);
  reporter.set(result.available ? "available" : (result.pending.empty() ? "up-to-date" : "ready"));
  return result;
}

std::string UpdateManager::stage(UpdateReporter &reporter, CancelToken &cancel, StageMode mode) {
  const auto work = platform::FileLock::tryAcquire(layout_.workLock());
  if (!work)
    throw Error("another Orchard update or repair is already running");
  std::error_code error;
  std::filesystem::remove(layout_.cancelFlag(), error);

  reporter.set("checking", "Checking for updates");
  const std::optional<InstallState> state = InstallState::load(layout_);
  if (!state)
    throw Error("Orchard is not installed in " + toUtf8(layout_.root));
  ReleaseClient client(server_, platform_);
  const ChannelRelease release = client.fetchChannel(state->channel(), cancel);
  requireFresh(*state, release);
  reporter.setVersions(state->channel(), state->current(), release.version);
  requireBootstrapper(release.minimumBootstrapper, release, client, cancel);

  if (!wanted(*state, release)) {
    InstallState::modify(layout_, [&](InstallState &s) {
      s.setNumber("lastCheck", unixNow());
      s.setSequence(release.channel, release.sequence);
    });
    try {
      updateBootstrapper(layout_, release, platform_, client, cancel);
    } catch (const Error &e) {
      log::warn(std::string("bootstrapper update failed: ") + e.what());
    }
    reporter.set(state->pending().empty() ? "up-to-date" : "ready");
    return {};
  }

  const auto [manifest, envelope] = client.fetchManifest(release, cancel);
  requireBootstrapper(manifest.minimumBootstrapper, release, client, cancel);
  log::info("staging " + manifest.version + " from channel " + release.channel);

  ObjectStore store(layout_.objectsDir());
  const Stager stager(layout_, *state, platform_);
  StagePlan plan = stager.plan(manifest, store);
  reporter.set("downloading", "Preparing");
  stager.reuseInstalled(plan, store, cancel);
  requireDiskSpace(layout_.root, plan.downloadBytes + plan.buildBytes);
  log::info("downloading " + std::to_string(plan.objects.size()) + " objects, " +
            std::to_string(plan.downloadBytes) + " bytes; " + std::to_string(plan.components.size()) +
            " components to build");

  reporter.set("downloading", state->current().empty() ? "Downloading Orchard" : "Downloading update");
  reporter.bindCancel(cancel);
  Downloader(client.objectsUrl(), store).fetch(plan.objects, cancel, [&](std::uint64_t done, std::uint64_t total) {
    reporter.progress(done, total);
  });

  reporter.set("staging", "Installing files");
  stager.build(manifest, plan, store, cancel);
  saveManifest(layout_, platform_, envelope, manifest);
  InstallState::modify(layout_, [&](InstallState &s) {
    InstallState::VersionInfo info;
    for (const Component &component : manifest.components) {
      s.recordComponent(component.name, component.version, component.digest);
      info.components[component.name] = component.version;
    }
    info.launch = manifest.launch.raw;
    info.icon = manifest.icon;
    s.putVersion(manifest.version, info);
    s.setSequence(release.channel, release.sequence);
    s.setNumber("lastCheck", unixNow());
    s.setFlag("channelChanged", false);
    if (mode == StageMode::Activate)
      s.activate(manifest.version);
    else
      s.set("pending", manifest.version);
  });
  // Every staged file was verified; the cache has done its job.
  store.clear();

  try {
    updateBootstrapper(layout_, release, platform_, client, cancel);
  } catch (const Error &e) {
    log::warn(std::string("bootstrapper update failed: ") + e.what());
  }
  reporter.set(mode == StageMode::Activate ? "up-to-date" : "ready");
  log::info("staged " + manifest.version);
  return manifest.version;
}

} // namespace orchard::boot
