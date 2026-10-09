/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "update/SelfUpdate.hpp"

#include "install/InstallState.hpp"
#include "network/Downloader.hpp"
#include "platform/Platform.hpp"
#include "update/ReleaseClient.hpp"
#include "util/Log.hpp"
#include "util/Version.hpp"

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

fs::path sibling(const Layout &layout, const char *tag) {
  const fs::path current = layout.bootstrapper();
  return current.parent_path() / fromUtf8(toUtf8(current.stem()) + "." + tag + toUtf8(current.extension()));
}

} // namespace

bool updateBootstrapper(const Layout &layout, const ChannelRelease &release, const std::string &platform,
                        ReleaseClient &client, const CancelToken &cancel) {
  const auto entry = release.bootstrappers.find(platform);
  if (entry == release.bootstrappers.end())
    return false;
  std::string installed = ORCHARD_BOOTSTRAPPER_VERSION;
  if (const auto state = InstallState::load(layout)) {
    if (const std::string recorded = state->get("bootstrapperVersion"); !recorded.empty())
      installed = recorded;
  }
  if (!versionLess(installed, release.bootstrapperVersion))
    return false;

  log::info("updating bootstrapper " + installed + " -> " + release.bootstrapperVersion);
  ObjectStore store(layout.objectsDir());
  Downloader(client.objectsUrl(), store, 2).fetch(entry->second.chunks, cancel, {});
  const fs::path replacement = sibling(layout, "new");
  assembleFile(entry->second, replacement, store);
  platform::makeExecutable(replacement);
  platform::replaceExecutable(layout.bootstrapper(), replacement, sibling(layout, "old"));
  InstallState::modify(layout, [&](InstallState &state) { state.set("bootstrapperVersion", release.bootstrapperVersion); });
  return true;
}

void removeOldBootstrapper(const Layout &layout) {
  std::error_code error;
  // Fails quietly while the old binary is still running on Windows; next time.
  fs::remove(sibling(layout, "old"), error);
  fs::remove(sibling(layout, "new"), error);
}

} // namespace orchard::boot
