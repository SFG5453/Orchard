/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <optional>
#include <string>
#include <vector>

namespace orchard::boot {

enum class Command {
  Launch,           // default: start Orchard, installing first if needed
  Install,          // install (or resume installing), then launch
  CheckUpdate,      // network check; prints JSON
  Update,           // download and stage; activates on next launch
  UpdateOnExit,     // wait for --wait-pid, activate the staged update, relaunch
  Status,           // print install + update state as JSON
  Repair,           // verify files, redownload only damaged ones
  Rollback,         // switch back to the previous version
  SetChannel,       // --channel on its own
  SetAutoUpdate,    // --auto-update on|off
  ConfirmHealthy,   // the app reports a good start
  Cancel,           // stop a running download
  Uninstall,
  BackgroundUpdate, // spawned after launch; rate-limited check + stage + cleanup
  Help,
  Version,
};

struct Options {
  Command command = Command::Launch;
  std::optional<std::string> root;
  std::string server;
  std::optional<std::string> channel;
  bool autoUpdate = true;
  int waitPid = 0;
  bool noLaunch = false;
  bool assumeYes = false;
  bool verbose = false;
  std::vector<std::string> appArgs; // positional arguments and everything after "--"
};

// Throws Error on unknown flags or missing values.
Options parseOptions(const std::vector<std::string> &args);
const char *usage();

} // namespace orchard::boot
