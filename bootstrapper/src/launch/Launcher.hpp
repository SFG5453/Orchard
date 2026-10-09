/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/InstallState.hpp"
#include "install/Layout.hpp"
#include "platform/Platform.hpp"

#include <string>
#include <vector>

namespace orchard::boot {

// Starts an installed version with the environment its manifest asked for:
// {app}, {root} and {component:name} expand to directories in the install,
// and ORCHARD_BOOTSTRAPPER / ORCHARD_INSTALL_ROOT / ORCHARD_INSTALLED_VERSION
// tell the app how to reach the updater.
platform::Process launchVersion(const Layout &layout, const InstallState &state, const std::string &version,
                                const std::vector<std::string> &args);

} // namespace orchard::boot
