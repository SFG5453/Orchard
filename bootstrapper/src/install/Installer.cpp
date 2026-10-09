/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "install/Installer.hpp"

#include "install/Activation.hpp"
#include "install/InstallState.hpp"
#include "platform/Platform.hpp"
#include "update/UpdateManager.hpp"
#include "util/Log.hpp"

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

void installSelf(const Layout &layout) {
  const fs::path self = platform::selfExecutable();
  const fs::path target = layout.bootstrapper();
  std::error_code error;
  if (fs::equivalent(self, target, error))
    return;
  fs::path copy = target;
  copy += ".copy";
  copyFile(self, copy);
  platform::makeExecutable(copy);
  fs::path backup = target;
  backup += ".bak";
  platform::replaceExecutable(target, copy, backup);
  fs::remove(backup, error);
}

} // namespace

std::string installFresh(const Layout &layout, const std::string &server, const std::string &platform,
                         const std::string &channel, ProgressUi &ui, CancelToken &cancel) {
  ui.setHeadline("Installing Orchard");
  ui.setStatus("Preparing");
  fs::create_directories(layout.root);
  log::open(layout.logFile());
  {
    const auto lock = platform::FileLock::acquire(layout.stateLock(), std::chrono::seconds(10));
    if (!lock)
      throw Error("install state is locked by another Orchard process");
    if (!InstallState::load(layout))
      InstallState::fresh(channel, platform).save(layout);
  }
  installSelf(layout);
  InstallState::modify(layout, [&](InstallState &state) {
    state.set("bootstrapperVersion", ORCHARD_BOOTSTRAPPER_VERSION);
    if (state.platform().empty())
      state.set("platform", platform);
  });
  log::info("installing into " + toUtf8(layout.root));

  UpdateReporter reporter(layout, &ui);
  UpdateManager(layout, server, platform).stage(reporter, cancel, StageMode::Activate);
  const std::optional<InstallState> state = InstallState::load(layout);
  if (!state || state->current().empty())
    throw Error("the release channel has no Orchard build for " + platform);
  refreshIntegration(layout);
  log::info("installed " + state->current());
  return state->current();
}

void uninstall(const Layout &layout, ProgressUi &ui) {
  if (!ui.confirm("Remove Orchard from this computer? Your library, settings and downloads are kept."))
    throw Cancelled();
  const auto work = platform::FileLock::tryAcquire(layout.workLock());
  if (!work)
    throw Error("an Orchard update is running; try again when it finishes");
  if (const std::optional<InstallState> state = InstallState::load(layout))
    platform::unregisterInstallation(state->created());
  log::info("uninstalling " + toUtf8(layout.root));
  platform::removeInstallRoot(layout.root);
}

} // namespace orchard::boot
