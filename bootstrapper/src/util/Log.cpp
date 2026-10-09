/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "util/Log.hpp"

#include "platform/Platform.hpp"

#include <chrono>
#include <cstdio>
#include <mutex>

namespace orchard::boot::log {

namespace {

std::mutex mutex;
std::FILE *file = nullptr;
bool echo = false;

} // namespace

void open(const std::filesystem::path &path) {
  std::lock_guard lock(mutex);
  if (file)
    return;
  std::error_code error;
  std::filesystem::create_directories(path.parent_path(), error);
  if (std::filesystem::file_size(path, error) > (1u << 20) && !error) {
    std::filesystem::path old = path;
    old += ".1";
    std::filesystem::rename(path, old, error);
  }
  file = platform::openFile(path, "ab");
}

void setEcho(bool value) {
  std::lock_guard lock(mutex);
  echo = value;
}

void write(std::string_view level, std::string_view message) {
  using namespace std::chrono;
  const auto now = floor<seconds>(system_clock::now());
  const auto day = floor<days>(now);
  const year_month_day date{day};
  const hh_mm_ss time{now - day};
  char stamp[32];
  std::snprintf(stamp, sizeof stamp, "%04d-%02u-%02uT%02d:%02d:%02dZ", int(date.year()), unsigned(date.month()),
                unsigned(date.day()), int(time.hours().count()), int(time.minutes().count()),
                int(time.seconds().count()));
  std::lock_guard lock(mutex);
  if (file) {
    std::fprintf(file, "%s [%d] %.*s: %.*s\n", stamp, platform::currentPid(), int(level.size()), level.data(),
                 int(message.size()), message.data());
    std::fflush(file);
  }
  if (echo || level == "error")
    std::fprintf(stderr, "orchard: %.*s\n", int(message.size()), message.data());
}

} // namespace orchard::boot::log
