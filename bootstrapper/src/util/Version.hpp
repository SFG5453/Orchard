/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <string_view>

namespace orchard::boot {

// Semver-style ordering: numeric fields compare numerically, a release sorts
// after its pre-releases ("4.2.0-canary.3" < "4.2.0"), "+build" is ignored.
int compareVersions(std::string_view a, std::string_view b);

inline bool versionLess(std::string_view a, std::string_view b) { return compareVersions(a, b) < 0; }

} // namespace orchard::boot
