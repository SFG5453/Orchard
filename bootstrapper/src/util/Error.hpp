/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <atomic>
#include <stdexcept>
#include <string>

namespace orchard::boot {

// Every recoverable failure is an Error; main turns it into a message and exit code.
struct Error : std::runtime_error {
  using std::runtime_error::runtime_error;
};

struct Cancelled : Error {
  Cancelled() : Error("cancelled") {}
};

// Release metadata needs a newer bootstrapper than this one.
struct TooOld : Error {
  using Error::Error;
};

// The bootstrapper replaced itself; rerun the same command with the new binary.
struct Relaunch : Error {
  Relaunch() : Error("bootstrapper updated; relaunching") {}
};

class CancelToken {
public:
  void cancel() { flag_.store(true, std::memory_order_relaxed); }
  bool cancelled() const { return flag_.load(std::memory_order_relaxed); }
  void throwIfCancelled() const {
    if (cancelled())
      throw Cancelled();
  }

private:
  std::atomic<bool> flag_{false};
};

} // namespace orchard::boot
