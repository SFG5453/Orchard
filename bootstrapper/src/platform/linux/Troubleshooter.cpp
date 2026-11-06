/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/Platform.hpp"
#include "platform/linux/Spawn.hpp"
#include "util/Error.hpp"
#include "util/Files.hpp"

#include <cstdlib>
#include <sstream>
#include <sys/wait.h>
#include <unistd.h>

namespace orchard::boot::platform {
namespace {
fs::path homePath(const char *variable, const char *fallback) {
  if (const char *value = std::getenv(variable); value && *value == '/')
    return value;
  const char *home = std::getenv("HOME");
  if (!home || *home != '/')
    throw Error("HOME is not set to an absolute path");
  return fs::path(home) / fallback;
}

int dialog(const std::vector<std::string> &args) {
  SpawnSpec spec;
  spec.program = "zenity";
  spec.argv = {"zenity", "--title=Orchard Troubleshooter", "--width=420"};
  spec.argv.insert(spec.argv.end(), args.begin(), args.end());
  const pid_t pid = spawnProcess(spec);
  int status = 0;
  while (::waitpid(pid, &status, 0) < 0) {
    if (errno != EINTR)
      throw Error("could not wait for the troubleshooter dialog");
  }
  return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

void resetWindow(const fs::path &settings) {
  if (!fs::exists(settings))
    return;
  std::istringstream input(readFile(settings));
  std::string line, result;
  bool window = false;
  while (std::getline(input, line)) {
    if (!line.empty() && line.back() == '\r')
      line.pop_back();
    if (!line.empty() && line.front() == '[' && line.back() == ']')
      window = line == "[window]";
    if (!window)
      result += line + '\n';
  }
  writeFileAtomic(settings, result);
}
}

void troubleshoot(int pid, bool crashed) {
  const char *display = std::getenv("DISPLAY"), *wayland = std::getenv("WAYLAND_DISPLAY");
  if ((!display || !*display) && (!wayland || !*wayland))
    return;
  try {
    const std::string intro = crashed ? "Orchard closed while starting. " : "";
    const int choice = dialog({"--question", "--text=" + intro + "Reset window settings to fix a missing window?",
                               "--ok-label=Fix window", "--cancel-label=More options"});
    bool clear = false;
    if (choice != 0) {
      if (choice != 1 || dialog({"--question", "--text=Clear Orchard app data and settings? This deletes downloads and local library data. Saved system credentials may remain.",
                                 "--ok-label=Clear app data", "--cancel-label=Cancel"}) != 0)
        return;
      clear = true;
    }
    if (pid > 0) {
      if (dialog({"--question", "--text=Quit Orchard completely, then select Continue.", "--ok-label=Continue"}) != 0)
        return;
      if (!waitForPid(pid, std::chrono::seconds(3)))
        throw Error("Orchard is still running. Quit Orchard and try again.");
    }
    const fs::path settings = homePath("XDG_CONFIG_HOME", ".config") / "SFG545" / "Orchard.conf";
    if (clear) {
      for (const fs::path &path : {settings, homePath("XDG_DATA_HOME", ".local/share") / "SFG545" / "Orchard",
                                   homePath("XDG_CACHE_HOME", ".cache") / "SFG545" / "Orchard"}) {
        if (!removeTree(path))
          throw Error("Could not remove " + toUtf8(path));
      }
    } else {
      resetWindow(settings);
    }
    dialog({"--info", "--text=Reset complete. Start Orchard again."});
  } catch (const std::exception &e) {
    try { dialog({"--error", "--text=" + std::string(e.what())}); } catch (...) {}
  }
}
} // namespace orchard::boot::platform
