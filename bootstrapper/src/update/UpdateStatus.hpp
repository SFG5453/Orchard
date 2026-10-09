/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/Layout.hpp"
#include "ui/ProgressUi.hpp"
#include "util/Json.hpp"

#include <chrono>
#include <cstdint>
#include <string>

namespace orchard::boot {

// update-state.json: the app's read side of the IPC. Written atomically, so a
// reader sees either the old or the new document.
//   {"state":"downloading", "channel":"stable", "current":"4.2.14",
//    "latest":"4.2.15", "done":1048576, "total":5242880, "error":"",
//    "pid":4242, "updated":1790000000}
// state: idle | checking | up-to-date | available | downloading | staging |
//        ready | error | cancelled
class UpdateReporter {
public:
  // `ui` may be null for background work.
  UpdateReporter(const Layout &layout, ProgressUi *ui);

  void set(const std::string &state, const std::string &status = {});
  void setVersions(const std::string &channel, const std::string &current, const std::string &latest);
  // Also polls the UI cancel button and the cache/cancel flag file.
  void progress(std::uint64_t done, std::uint64_t total);
  void bindCancel(CancelToken &cancel) { cancel_ = &cancel; }
  void fail(const std::string &state, const std::string &message);
  ProgressUi *ui() const { return ui_; }

private:
  void write(bool force);

  const Layout &layout_;
  ProgressUi *ui_;
  CancelToken *cancel_ = nullptr;
  Json doc_;
  std::chrono::steady_clock::time_point lastWrite_{};
};

Json readUpdateStatus(const Layout &layout);

} // namespace orchard::boot
