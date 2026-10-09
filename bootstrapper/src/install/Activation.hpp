/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/InstallState.hpp"
#include "install/Layout.hpp"

#include <string>

namespace orchard::boot {

// Switches "current" to the staged release when one is ready. Only
// install.json changes; the directories were finished during staging.
// Returns the activated version, or empty.
std::string activatePending(const Layout &layout);

// Makes "previous" current again and skips the abandoned version so the
// background updater does not reinstall it. Returns the version now current.
std::string rollback(const Layout &layout, const std::string &reason);

// Removes versions other than current/previous/pending, components none of
// them use, and staging debris. Callers hold the work lock.
void cleanup(const Layout &layout, const std::string &platform);

// Refreshes shortcuts and uninstall registration for the current version.
void refreshIntegration(const Layout &layout);

} // namespace orchard::boot
