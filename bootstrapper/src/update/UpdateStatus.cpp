/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "update/UpdateStatus.hpp"

#include "platform/Platform.hpp"
#include "util/Log.hpp"

namespace orchard::boot {

UpdateReporter::UpdateReporter(const Layout &layout, ProgressUi *ui) : layout_(layout), ui_(ui) {
  doc_ = readUpdateStatus(layout);
  doc_["pid"] = platform::currentPid();
  doc_["error"] = "";
}

void UpdateReporter::set(const std::string &state, const std::string &status) {
  doc_["state"] = state;
  if (state != "downloading") {
    doc_["done"] = 0;
    doc_["total"] = 0;
  }
  if (ui_ && !status.empty())
    ui_->setStatus(status);
  write(true);
}

void UpdateReporter::setVersions(const std::string &channel, const std::string &current, const std::string &latest) {
  doc_["channel"] = channel;
  doc_["current"] = current;
  doc_["latest"] = latest;
}

void UpdateReporter::progress(std::uint64_t done, std::uint64_t total) {
  doc_["done"] = done;
  doc_["total"] = total;
  if (ui_)
    ui_->setProgress(done, total);
  std::error_code error;
  if (cancel_ && ((ui_ && ui_->cancelRequested()) || std::filesystem::exists(layout_.cancelFlag(), error)))
    cancel_->cancel();
  write(false);
}

void UpdateReporter::fail(const std::string &state, const std::string &message) {
  doc_["state"] = state;
  doc_["error"] = message;
  write(true);
}

void UpdateReporter::write(bool force) {
  const auto now = std::chrono::steady_clock::now();
  // Four writes a second is plenty for a progress bar.
  if (!force && now - lastWrite_ < std::chrono::milliseconds(250))
    return;
  lastWrite_ = now;
  doc_["updated"] = std::chrono::duration_cast<std::chrono::seconds>(
                        std::chrono::system_clock::now().time_since_epoch())
                        .count();
  try {
    writeFileAtomic(layout_.updateState(), doc_.dump() + "\n");
  } catch (const std::exception &e) {
    log::warn(std::string("cannot write update state: ") + e.what());
  }
}

Json readUpdateStatus(const Layout &layout) {
  try {
    return parseJson(readFile(layout.updateState(), 1u << 20), "update-state.json");
  } catch (const Error &) {
    return Json{{"state", "idle"}};
  }
}

} // namespace orchard::boot
