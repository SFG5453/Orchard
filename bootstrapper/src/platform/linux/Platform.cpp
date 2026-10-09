/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/Platform.hpp"

#include "network/MbedTlsHttp.hpp"
#include "platform/linux/Spawn.hpp"
#include "ui/ProgressUi.hpp"
#include "util/Error.hpp"
#include "util/Files.hpp"

#include <csignal>
#include <cstdlib>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>

extern char **environ;

namespace orchard::boot::platform {

namespace {

fs::path dataHome() {
  if (const char *xdg = std::getenv("XDG_DATA_HOME"); xdg && *xdg == '/')
    return xdg;
  const char *home = std::getenv("HOME");
  if (!home || !*home)
    throw Error("HOME is not set");
  return fs::path(home) / ".local" / "share";
}

// Desktop Entry spec quoting for the Exec key.
std::string quoteExec(const std::string &path) {
  std::string out = "\"";
  for (char c : path) {
    if (c == '\\')
      out += "\\\\\\\\";
    else if (c == '"' || c == '`' || c == '$')
      out += std::string("\\\\") + c;
    else
      out += c;
  }
  return out + "\"";
}

} // namespace

void initProcess() {
  // Sockets and the zenity pipe report EPIPE instead of killing us.
  std::signal(SIGPIPE, SIG_IGN);
}

std::vector<std::string> arguments(int argc, char **argv) { return {argv + std::min(argc, 1), argv + argc}; }

std::string platformId() {
#if defined(__x86_64__)
  return "linux-x86_64";
#elif defined(__aarch64__)
  return "linux-arm64";
#else
  return {};
#endif
}

fs::path selfExecutable() { return fs::read_symlink("/proc/self/exe"); }

std::string bootstrapperFileName() { return "orchard"; }

// Lowercase on purpose: Qt keeps user data under SFG545/Orchard.
fs::path defaultInstallRoot() { return dataHome() / "orchard"; }

std::FILE *openFile(const fs::path &path, const char *mode) { return std::fopen(path.c_str(), mode); }

bool seekFile(std::FILE *file, std::uint64_t offset) { return fseeko(file, static_cast<off_t>(offset), SEEK_SET) == 0; }

void syncFile(std::FILE *file) { fsync(fileno(file)); }

void makeExecutable(const fs::path &path) {
  fs::permissions(path, fs::perms::owner_exec | fs::perms::group_exec | fs::perms::others_exec,
                  fs::perm_options::add);
}

void replaceExecutable(const fs::path &target, const fs::path &replacement, const fs::path &backup) {
  std::error_code error;
  fs::remove(backup, error);
  if (fs::exists(target, error) && ::link(target.c_str(), backup.c_str()) != 0)
    copyFile(target, backup);
  // rename() swaps the directory entry; a running copy keeps its old inode.
  fs::rename(replacement, target);
}

std::unique_ptr<FileLock> FileLock::tryAcquire(const fs::path &path) {
  fs::create_directories(path.parent_path());
  const int fd = ::open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644);
  if (fd < 0)
    throw Error("cannot open lock file " + path.string());
  if (::flock(fd, LOCK_EX | LOCK_NB) != 0) {
    ::close(fd);
    return nullptr;
  }
  return std::unique_ptr<FileLock>(new FileLock(fd));
}

std::unique_ptr<FileLock> FileLock::acquire(const fs::path &path, std::chrono::milliseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (true) {
    if (auto lock = tryAcquire(path))
      return lock;
    if (std::chrono::steady_clock::now() > deadline)
      return nullptr;
    std::this_thread::sleep_for(std::chrono::milliseconds(25));
  }
}

FileLock::~FileLock() { ::close(static_cast<int>(handle_)); }

Environment currentEnvironment() {
  Environment env;
  for (char **entry = environ; entry && *entry; ++entry) {
    const std::string text = *entry;
    const std::size_t eq = text.find('=');
    if (eq != std::string::npos && eq > 0)
      env[text.substr(0, eq)] = text.substr(eq + 1);
  }
  return env;
}

std::string environmentKey(const Environment &, const std::string &name) { return name; }

char pathListSeparator() { return ':'; }

Process spawn(const fs::path &program, const SpawnOptions &options) {
  std::vector<std::string> argv = {program.string()};
  argv.insert(argv.end(), options.args.begin(), options.args.end());
  std::vector<std::string> envp;
  if (options.env) {
    for (const auto &[name, value] : *options.env)
      envp.push_back(name + "=" + value);
  }
  SpawnSpec spec;
  spec.program = program;
  spec.argv = std::move(argv);
  spec.envp = options.env ? &envp : nullptr;
  spec.workingDirectory = options.workingDirectory;
  spec.detach = options.background;
  const pid_t pid = spawnProcess(spec);
  return Process{pid, pid};
}

std::optional<int> waitProcess(Process &process, std::chrono::milliseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (process.pid > 0) {
    int status = 0;
    const pid_t done = ::waitpid(process.pid, &status, WNOHANG);
    if (done == process.pid || (done < 0 && errno == ECHILD)) {
      process.pid = 0;
      return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    }
    if (std::chrono::steady_clock::now() > deadline)
      return std::nullopt;
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  return std::nullopt;
}

// The child is reparented to init when we exit, which reaps it.
void releaseProcess(Process &process) { process = {}; }

bool waitForPid(int pid, std::chrono::milliseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (::kill(pid, 0) == 0 || errno == EPERM) {
    if (std::chrono::steady_clock::now() > deadline)
      return false;
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
  return true;
}

int currentPid() { return static_cast<int>(::getpid()); }

std::vector<std::string> registerInstallation(const fs::path &, const fs::path &bootstrapper, const fs::path &icon,
                                              const std::string &) {
  const fs::path data = dataHome();
  std::vector<std::string> created;
  std::string iconName = "orchard";
  std::error_code error;
  if (!icon.empty() && fs::exists(icon, error)) {
    const fs::path target = data / "icons" / "hicolor" / "512x512" / "apps" / "orchard.png";
    fs::create_directories(target.parent_path());
    copyFile(icon, target);
    created.push_back(target.string());
  }
  const fs::path desktop = data / "applications" / "orchard.desktop";
  writeFileAtomic(desktop, "[Desktop Entry]\n"
                           "Type=Application\n"
                           "Name=Orchard\n"
                           "Comment=Music player\n"
                           "Exec=" + quoteExec(bootstrapper.string()) + " %U\n"
                           "Icon=" + iconName + "\n"
                           "Terminal=false\n"
                           "Categories=AudioVideo;Audio;Player;\n"
                           "StartupWMClass=orchard\n");
  created.push_back(desktop.string());
  return created;
}

void unregisterInstallation(const std::vector<std::string> &created) {
  for (const std::string &entry : created) {
    std::error_code error;
    if (entry.starts_with("/"))
      fs::remove(entry, error);
  }
}

void removeInstallRoot(const fs::path &root) { removeTree(root); }

std::unique_ptr<HttpSession> createHttpSession() {
  std::vector<fs::path> trust;
  if (const char *file = std::getenv("SSL_CERT_FILE"); file && *file)
    trust.emplace_back(file);
  for (const char *path : {"/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt",
                           "/etc/ssl/ca-bundle.pem", "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
                           "/etc/ssl/cert.pem"}) {
    std::error_code error;
    if (trust.empty() && fs::exists(path, error))
      trust.emplace_back(path);
  }
  if (trust.empty())
    trust.emplace_back("/etc/ssl/certs");
  return createMbedTlsSession(trust);
}

bool stderrIsTerminal() { return ::isatty(STDERR_FILENO) == 1; }

} // namespace orchard::boot::platform
