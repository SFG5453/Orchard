/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/Layout.hpp"
#include "update/UpdateStatus.hpp"
#include "util/Error.hpp"

#include <cstddef>
#include <cstdint>
#include <string>

namespace orchard::boot {

struct RepairResult {
  std::size_t checked = 0;
  std::size_t damaged = 0;
  std::uint64_t downloaded = 0;
};

// Hashes every file of the current version and its components against the
// signed manifest, then rebuilds only the damaged ones. Good chunks inside a
// damaged file are reused, so a flipped byte costs one chunk of download.
RepairResult repair(const Layout &layout, const std::string &server, const std::string &platform,
                    UpdateReporter &reporter, CancelToken &cancel);

} // namespace orchard::boot
