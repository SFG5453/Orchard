/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace orchard::boot {
class HttpSession;
class ProgressUi;
} // namespace orchard::boot

// Everything OS-specific sits behind this header. One implementation per
// directory under platform/; the rest of the bootstrapper stays portable.
namespace orchard::boot::platform {

namespace fs = std::filesystem;

// Process setup (signals, console attachment, DPI) before anything else runs.
void initProcess();
// UTF-8 command line arguments, program name excluded.
std::vector<std::string> arguments(int argc, char **argv);

// "linux-x86_64", "win-x86_64", ... matching the manifest naming.
std::string platformId();
fs::path selfExecutable();
// File name of the installed bootstrapper inside the install root.
std::string bootstrapperFileName();
fs::path defaultInstallRoot();

std::FILE *openFile(const fs::path &path, const char *mode);
bool seekFile(std::FILE *file, std::uint64_t offset);
void syncFile(std::FILE *file);
void makeExecutable(const fs::path &path);

// Swap in a verified bootstrapper. Works while `target` is running; the old
// binary stays at `backup` as a fallback.
void replaceExecutable(const fs::path &target, const fs::path &replacement, const fs::path &backup);

// Exclusive advisory lock held for the object's lifetime.
class FileLock {
public:
  static std::unique_ptr<FileLock> tryAcquire(const fs::path &path);
  static std::unique_ptr<FileLock> acquire(const fs::path &path, std::chrono::milliseconds timeout);
  ~FileLock();

private:
  explicit FileLock(std::intptr_t handle) : handle_(handle) {}
  std::intptr_t handle_;
};

// Environment as name -> value. Names compare case-insensitively on Windows.
using Environment = std::map<std::string, std::string>;
Environment currentEnvironment();
// Finds the existing spelling of `name` ("Path" vs "PATH").
std::string environmentKey(const Environment &env, const std::string &name);
char pathListSeparator();

struct Process {
  std::intptr_t handle = 0;
  int pid = 0;
};

struct SpawnOptions {
  std::vector<std::string> args;
  std::optional<Environment> env;
  fs::path workingDirectory;
  // Detach from the console/session and drop stdio; for background helpers.
  bool background = false;
};

Process spawn(const fs::path &program, const SpawnOptions &options);
// Exit code, or nullopt while the process is still running after `timeout`.
std::optional<int> waitProcess(Process &process, std::chrono::milliseconds timeout);
void releaseProcess(Process &process);
// For processes we did not start. True once `pid` has exited.
bool waitForPid(int pid, std::chrono::milliseconds timeout);
int currentPid();

// Desktop integration (menu entries, uninstall registration). Returns every
// path or key created so uninstall can remove exactly those.
std::vector<std::string> registerInstallation(const fs::path &root, const fs::path &bootstrapper,
                                              const fs::path &icon, const std::string &version);
void unregisterInstallation(const std::vector<std::string> &created);
// Removes the install root, deferring to after exit where the OS keeps the
// running bootstrapper locked.
void removeInstallRoot(const fs::path &root);

std::unique_ptr<HttpSession> createHttpSession();
// Native progress window, or null when no display is available.
std::unique_ptr<ProgressUi> createGraphicalUi();
bool stderrIsTerminal();

} // namespace orchard::boot::platform
