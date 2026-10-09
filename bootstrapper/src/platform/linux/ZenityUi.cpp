/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/Platform.hpp"
#include "platform/linux/Spawn.hpp"
#include "ui/ProgressUi.hpp"

#include <chrono>
#include <csignal>
#include <fcntl.h>
#include <cstdlib>
#include <sys/wait.h>
#include <unistd.h>

namespace orchard::boot::platform {

namespace {

// zenity ships with GNOME and most KDE installs and draws a native progress
// dialog for the price of a pipe. Linking a toolkit for one window would
// outweigh the rest of the bootstrapper.
fs::path findZenity() {
  const char *path = std::getenv("PATH");
  std::string_view rest = path ? path : "";
  while (!rest.empty()) {
    const std::size_t colon = std::min(rest.find(':'), rest.size());
    const fs::path candidate = fs::path(rest.substr(0, colon)) / "zenity";
    if (::access(candidate.c_str(), X_OK) == 0)
      return candidate;
    rest.remove_prefix(std::min(colon + 1, rest.size()));
  }
  return {};
}

std::string escapeMarkup(const std::string &text) {
  std::string out;
  for (char c : text) {
    if (c == '&')
      out += "&amp;";
    else if (c == '<')
      out += "&lt;";
    else if (c == '>')
      out += "&gt;";
    else if (c != '\n' && c != '\r')
      out += c;
  }
  return out;
}

int runDialog(const fs::path &zenity, std::vector<std::string> args) {
  args.insert(args.begin(), {"zenity", "--title=Orchard", "--width=420"});
  SpawnSpec spec;
  spec.program = zenity;
  spec.argv = std::move(args);
  const pid_t pid = spawnProcess(spec);
  int status = 0;
  ::waitpid(pid, &status, 0);
  return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

class ZenityUi final : public ProgressUi {
public:
  explicit ZenityUi(fs::path zenity) : zenity_(std::move(zenity)) {}
  ~ZenityUi() override { close(); }

  void setHeadline(const std::string &text) override { headline_ = text; }
  void setStatus(const std::string &text) override {
    status_ = text;
    send("# " + escapeMarkup(status_) + "\n");
  }
  void setProgress(std::uint64_t done, std::uint64_t total) override {
    const auto now = std::chrono::steady_clock::now();
    if (now - last_ < std::chrono::milliseconds(150))
      return;
    last_ = now;
    if (total == 0)
      return;
    // 100 would let zenity consider the job finished.
    const std::uint64_t percent = std::min<std::uint64_t>(99, 100 * done / total);
    send(std::to_string(percent) + "\n# " + escapeMarkup(status_) + "    " + formatBytes(done) + " / " +
         formatBytes(total) + "\n");
  }
  bool cancelRequested() override {
    if (pid_ > 0 && !cancelled_) {
      int status = 0;
      if (::waitpid(pid_, &status, WNOHANG) == pid_) {
        pid_ = 0;
        cancelled_ = true;
      }
    }
    return cancelled_;
  }
  bool confirm(const std::string &question) override {
    return runDialog(zenity_, {"--question", "--text=" + escapeMarkup(question)}) == 0;
  }
  void showError(const std::string &message) override {
    close();
    runDialog(zenity_, {"--error", "--text=" + escapeMarkup(message)});
  }
  void close() override {
    if (pipe_ >= 0)
      ::close(pipe_);
    pipe_ = -1;
    if (pid_ > 0) {
      ::kill(pid_, SIGTERM);
      ::waitpid(pid_, nullptr, 0);
    }
    pid_ = 0;
  }

private:
  void send(const std::string &line) {
    if (cancelled_)
      return;
    if (pid_ == 0)
      start();
    if (pipe_ >= 0 && ::write(pipe_, line.data(), line.size()) < 0)
      cancelRequested();
  }

  void start() {
    int fds[2];
    if (::pipe2(fds, O_CLOEXEC) != 0)
      return;
    SpawnSpec spec;
    spec.program = zenity_;
    spec.argv = {"zenity", "--progress", "--title=Orchard", "--width=420", "--percentage=0",
                 "--text=" + escapeMarkup(headline_.empty() ? std::string("Orchard") : headline_)};
    spec.stdinFd = fds[0];
    try {
      pid_ = spawnProcess(spec);
    } catch (const std::exception &) {
      ::close(fds[1]);
      fds[1] = -1;
    }
    ::close(fds[0]);
    pipe_ = fds[1];
  }

  fs::path zenity_;
  std::string headline_;
  std::string status_;
  pid_t pid_ = 0;
  int pipe_ = -1;
  bool cancelled_ = false;
  std::chrono::steady_clock::time_point last_{};
};

} // namespace

std::unique_ptr<ProgressUi> createGraphicalUi() {
  const char *x11 = std::getenv("DISPLAY");
  const char *wayland = std::getenv("WAYLAND_DISPLAY");
  if ((!x11 || !*x11) && (!wayland || !*wayland))
    return nullptr;
  fs::path zenity = findZenity();
  if (zenity.empty())
    return nullptr;
  return std::make_unique<ZenityUi>(std::move(zenity));
}

} // namespace orchard::boot::platform
