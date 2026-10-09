/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/Layout.hpp"
#include "ui/ProgressUi.hpp"
#include "util/Error.hpp"

#include <string>

namespace orchard::boot {

// Fresh install: create the root, copy this binary in as the bootstrapper,
// stage the channel's release and activate it immediately. An interrupted
// install resumes from the verified cache next time.
std::string installFresh(const Layout &layout, const std::string &server, const std::string &platform,
                         const std::string &channel, ProgressUi &ui, CancelToken &cancel);

// Removes shortcuts, the uninstall entry and the install root. User data
// (library, settings, downloads) lives elsewhere and is left alone.
void uninstall(const Layout &layout, ProgressUi &ui);

} // namespace orchard::boot
