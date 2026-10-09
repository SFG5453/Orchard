/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <filesystem>
#include <string>
#include <sys/types.h>
#include <vector>

namespace orchard::boot::platform {

struct SpawnSpec {
  std::filesystem::path program;
  std::vector<std::string> argv; // argv[0] included
  const std::vector<std::string> *envp = nullptr; // null inherits
  std::filesystem::path workingDirectory;
  bool detach = false; // new session, stdio on /dev/null
  int stdinFd = -1;    // dup'ed onto the child's stdin when set
};

// posix_spawn wrapper; restores default SIGPIPE handling in the child.
pid_t spawnProcess(const SpawnSpec &spec);

} // namespace orchard::boot::platform
