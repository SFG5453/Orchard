/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/InstallState.hpp"
#include "install/Layout.hpp"
#include "update/Manifest.hpp"
#include "update/UpdateStatus.hpp"
#include "util/Error.hpp"

#include <cstdint>
#include <string>

namespace orchard::boot {

struct UpdateCheck {
  std::string channel;
  std::string current;
  std::string latest;
  std::string pending;
  bool available = false;
  std::uint64_t downloadBytes = 0; // upper bound; installed bytes are reused when possible
};

enum class StageMode {
  Pending,  // activate on the next launch
  Activate, // fresh install: switch immediately
};

// check manifest -> download missing objects -> verify -> build staging
// directories -> rename into place -> record in install.json -> mark ready.
// The running version is never touched.
class UpdateManager {
public:
  UpdateManager(const Layout &layout, std::string server, std::string platform);

  UpdateCheck check(UpdateReporter &reporter, const CancelToken &cancel);
  // Returns the staged version, or empty when nothing newer is available.
  std::string stage(UpdateReporter &reporter, CancelToken &cancel, StageMode mode);

private:
  bool wanted(const InstallState &state, const ChannelRelease &release) const;
  void requireBootstrapper(const std::string &minimum, const ChannelRelease &release, class ReleaseClient &client,
                           const CancelToken &cancel);

  const Layout &layout_;
  std::string server_;
  std::string platform_;
};

} // namespace orchard::boot
