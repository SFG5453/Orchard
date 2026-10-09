/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/linux/Spawn.hpp"

#include "util/Error.hpp"

#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <spawn.h>
#include <unistd.h>

extern char **environ;

namespace orchard::boot::platform {

pid_t spawnProcess(const SpawnSpec &spec) {
  std::vector<char *> argv;
  for (const std::string &arg : spec.argv)
    argv.push_back(const_cast<char *>(arg.c_str()));
  argv.push_back(nullptr);
  std::vector<char *> envp;
  if (spec.envp) {
    for (const std::string &entry : *spec.envp)
      envp.push_back(const_cast<char *>(entry.c_str()));
    envp.push_back(nullptr);
  }

  posix_spawn_file_actions_t actions;
  posix_spawnattr_t attr;
  posix_spawn_file_actions_init(&actions);
  posix_spawnattr_init(&attr);
  short flags = POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK;
  // Ignored signals survive exec; Orchard should not inherit our SIGPIPE policy.
  sigset_t defaults;
  sigemptyset(&defaults);
  sigaddset(&defaults, SIGPIPE);
  posix_spawnattr_setsigdefault(&attr, &defaults);
  sigset_t mask;
  sigemptyset(&mask);
  posix_spawnattr_setsigmask(&attr, &mask);
  if (spec.detach) {
    flags |= POSIX_SPAWN_SETSID;
    for (int fd : {STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO})
      posix_spawn_file_actions_addopen(&actions, fd, "/dev/null", fd == STDIN_FILENO ? O_RDONLY : O_WRONLY, 0);
  }
  if (spec.stdinFd >= 0)
    posix_spawn_file_actions_adddup2(&actions, spec.stdinFd, STDIN_FILENO);
  if (!spec.workingDirectory.empty())
    posix_spawn_file_actions_addchdir_np(&actions, spec.workingDirectory.c_str());
  posix_spawnattr_setflags(&attr, flags);

  pid_t pid = 0;
  const int error = spec.program.has_parent_path()
                        ? posix_spawn(&pid, spec.program.c_str(), &actions, &attr, argv.data(),
                                      spec.envp ? envp.data() : environ)
                        : posix_spawnp(&pid, spec.program.c_str(), &actions, &attr, argv.data(),
                                       spec.envp ? envp.data() : environ);
  posix_spawn_file_actions_destroy(&actions);
  posix_spawnattr_destroy(&attr);
  if (error != 0)
    throw Error("cannot start " + spec.program.string() + ": " + std::strerror(error));
  return pid;
}

} // namespace orchard::boot::platform
