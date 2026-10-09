/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "platform/Platform.hpp"
#include "ui/ProgressUi.hpp"

#include <chrono>
#include <cstdio>

namespace orchard::boot {

namespace {

class ConsoleUi final : public ProgressUi {
public:
  void setHeadline(const std::string &text) override {
    endLine();
    std::fprintf(stderr, "%s\n", text.c_str());
  }
  void setStatus(const std::string &text) override {
    status_ = text;
    draw(true);
  }
  void setProgress(std::uint64_t done, std::uint64_t total) override {
    done_ = done;
    total_ = total;
    draw(false);
  }
  bool cancelRequested() override { return false; }
  bool confirm(const std::string &question) override {
    endLine();
    std::fprintf(stderr, "%s [y/N] ", question.c_str());
    char answer[16] = {};
    if (!std::fgets(answer, sizeof answer, stdin))
      return false;
    return answer[0] == 'y' || answer[0] == 'Y';
  }
  void showError(const std::string &message) override {
    endLine();
    std::fprintf(stderr, "error: %s\n", message.c_str());
  }
  void close() override { endLine(); }

private:
  void draw(bool force) {
    const auto now = std::chrono::steady_clock::now();
    if (!force && now - last_ < std::chrono::milliseconds(200))
      return;
    last_ = now;
    std::string line = "  " + status_;
    if (total_ > 0) {
      const int width = 24;
      const int filled = static_cast<int>(width * done_ / total_);
      line += "  [" + std::string(filled, '#') + std::string(width - filled, '-') + "] " +
              std::to_string(100 * done_ / total_) + "%  " + formatBytes(done_) + " / " + formatBytes(total_);
    }
    std::fprintf(stderr, "\r%-100s", line.c_str());
    std::fflush(stderr);
    dirty_ = true;
  }
  void endLine() {
    if (dirty_)
      std::fprintf(stderr, "\n");
    dirty_ = false;
  }

  std::string status_;
  std::uint64_t done_ = 0, total_ = 0;
  std::chrono::steady_clock::time_point last_{};
  bool dirty_ = false;
};

class NullUi final : public ProgressUi {
public:
  explicit NullUi(bool yes) : yes_(yes) {}
  void setHeadline(const std::string &) override {}
  void setStatus(const std::string &) override {}
  void setProgress(std::uint64_t, std::uint64_t) override {}
  bool cancelRequested() override { return false; }
  bool confirm(const std::string &) override { return yes_; }
  void showError(const std::string &message) override { std::fprintf(stderr, "error: %s\n", message.c_str()); }
  void close() override {}

private:
  bool yes_;
};

} // namespace

std::unique_ptr<ProgressUi> createConsoleUi() { return std::make_unique<ConsoleUi>(); }

std::unique_ptr<ProgressUi> createNullUi(bool assumeYes) { return std::make_unique<NullUi>(assumeYes); }

std::unique_ptr<ProgressUi> createUi(bool assumeYes) {
  if (platform::stderrIsTerminal())
    return createConsoleUi();
  if (auto gui = platform::createGraphicalUi())
    return gui;
  return createNullUi(assumeYes);
}

std::string formatBytes(std::uint64_t bytes) {
  if (bytes < (1u << 20))
    return std::to_string((bytes + 1023) / 1024) + " KB";
  if (bytes < (1ull << 30))
    return std::to_string((bytes + (1u << 19)) >> 20) + " MB";
  char text[32];
  std::snprintf(text, sizeof text, "%.1f GB", double(bytes) / double(1ull << 30));
  return text;
}

} // namespace orchard::boot
