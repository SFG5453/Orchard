/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace orchard::boot {

// The only window the bootstrapper ever shows. It appears for installs,
// repairs and foreground updates; a normal launch never creates one.
class ProgressUi {
public:
  virtual ~ProgressUi() = default;
  virtual void setHeadline(const std::string &text) = 0; // "Installing Orchard"
  virtual void setStatus(const std::string &text) = 0;   // "Downloading application"
  // total == 0 shows an indeterminate bar.
  virtual void setProgress(std::uint64_t done, std::uint64_t total) = 0;
  virtual bool cancelRequested() = 0;
  virtual bool confirm(const std::string &question) = 0;
  // Blocks until the user dismisses it.
  virtual void showError(const std::string &message) = 0;
  virtual void close() = 0;
};

std::unique_ptr<ProgressUi> createConsoleUi();
std::unique_ptr<ProgressUi> createNullUi(bool assumeYes);
// Console on a terminal, then the native window, then nothing.
std::unique_ptr<ProgressUi> createUi(bool assumeYes);

std::string formatBytes(std::uint64_t bytes);

} // namespace orchard::boot
