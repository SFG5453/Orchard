/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "app/Cli.hpp"

#include "update/ReleaseClient.hpp"
#include "util/Error.hpp"

#include <map>

namespace orchard::boot {

Options parseOptions(const std::vector<std::string> &args) {
  static const std::map<std::string, Command> commands = {
      {"--launch", Command::Launch},
      {"--install", Command::Install},
      {"--check-update", Command::CheckUpdate},
      {"--update", Command::Update},
      {"--update-on-exit", Command::UpdateOnExit},
      {"--status", Command::Status},
      {"--repair", Command::Repair},
      {"--rollback", Command::Rollback},
      {"--confirm-healthy", Command::ConfirmHealthy},
      {"--cancel", Command::Cancel},
      {"--uninstall", Command::Uninstall},
      {"--background-update", Command::BackgroundUpdate},
      {"--help", Command::Help},
      {"-h", Command::Help},
      {"--version", Command::Version},
  };
  Options options;
  options.server = kDefaultServer;
  bool commandSet = false;
  for (std::size_t i = 0; i < args.size(); ++i) {
    const std::string &arg = args[i];
    const auto value = [&]() -> const std::string & {
      if (i + 1 >= args.size())
        throw Error(arg + " needs a value");
      return args[++i];
    };
    if (arg == "--") {
      options.appArgs.insert(options.appArgs.end(), args.begin() + static_cast<long>(i) + 1, args.end());
      break;
    }
    if (const auto it = commands.find(arg); it != commands.end()) {
      if (commandSet && options.command != it->second)
        throw Error("only one command may be given (" + arg + ")");
      options.command = it->second;
      commandSet = true;
    } else if (arg == "--root") {
      options.root = value();
    } else if (arg == "--server") {
      options.server = value();
      if (!options.server.starts_with("https://"))
        throw Error("--server must be an https:// URL");
    } else if (arg == "--channel") {
      options.channel = value();
    } else if (arg == "--auto-update") {
      const std::string &mode = value();
      if (mode != "on" && mode != "off")
        throw Error("--auto-update takes on or off");
      if (commandSet && options.command != Command::SetAutoUpdate)
        throw Error("only one command may be given (" + arg + ")");
      options.command = Command::SetAutoUpdate;
      options.autoUpdate = mode == "on";
      commandSet = true;
    } else if (arg == "--wait-pid") {
      options.waitPid = std::stoi(value());
    } else if (arg == "--no-launch") {
      options.noLaunch = true;
    } else if (arg == "--yes" || arg == "-y") {
      options.assumeYes = true;
    } else if (arg == "--verbose" || arg == "-v") {
      options.verbose = true;
    } else if (arg.starts_with("-")) {
      throw Error("unknown option " + arg + " (see --help)");
    } else {
      // File paths and URLs pass through to Orchard itself.
      options.appArgs.push_back(arg);
    }
  }
  if (options.channel && !commandSet)
    options.command = Command::SetChannel;
  return options;
}

const char *usage() {
  return "Usage: orchard [command] [options] [-- orchard arguments]\n"
         "\n"
         "Commands:\n"
         "  --launch             Start Orchard (default). Installs first if needed.\n"
         "  --install            Install or finish installing Orchard, then start it.\n"
         "  --check-update       Check the release channel and print JSON.\n"
         "  --update             Download and stage the latest release.\n"
         "  --update-on-exit     Wait for --wait-pid to exit, apply the staged update, restart.\n"
         "  --status             Print install and update state as JSON.\n"
         "  --repair             Verify installed files and redownload damaged ones.\n"
         "  --rollback           Switch back to the previous version.\n"
         "  --channel NAME       Switch to the stable or canary channel.\n"
         "  --auto-update on|off Download updates in the background after launch.\n"
         "  --confirm-healthy    Mark the running version as healthy.\n"
         "  --cancel             Cancel a running download.\n"
         "  --uninstall          Remove Orchard. Your library and settings are kept.\n"
         "  --version            Print the bootstrapper version.\n"
         "\n"
         "Options:\n"
         "  --root DIR           Install directory.\n"
         "  --server URL         Release server (https only; signatures still apply).\n"
         "  --wait-pid PID       Process to wait for (with --update-on-exit).\n"
         "  --no-launch          Do not start Orchard afterwards.\n"
         "  --yes                Answer yes to confirmations.\n"
         "  --verbose            Echo the log to stderr.\n";
}

} // namespace orchard::boot
