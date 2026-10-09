/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "app/Cli.hpp"
#include "install/InstallState.hpp"
#include "install/Layout.hpp"

#include <string>
#include <vector>

namespace orchard::boot {

// Maps one command line onto the install/update/launch pieces. Holds no
// logic of its own beyond ordering and error reporting.
class Bootstrapper {
public:
  explicit Bootstrapper(std::vector<std::string> args);
  int run();

private:
  int dispatch();
  int launch();
  int install();
  int startAndWatch(bool allowRollback);
  int checkUpdate();
  int update();
  int updateOnExit();
  int status();
  int repairInstall();
  int rollbackInstall();
  int setChannel(bool print);
  int setAutoUpdate();
  int confirmHealthy();
  int cancelDownload();
  int uninstallInstall();
  int backgroundUpdate();

  // A downloaded installer defers to the installed bootstrapper, which may be newer.
  bool runsInstalledCopy() const;
  int delegate() const;
  void scheduleBackgroundUpdate(const InstallState &state) const;
  bool interactive() const;

  std::vector<std::string> args_;
  Options options_;
  Layout layout_;
  std::string platform_;
};

} // namespace orchard::boot
