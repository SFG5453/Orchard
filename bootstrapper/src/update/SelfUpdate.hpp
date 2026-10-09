/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "install/Layout.hpp"
#include "update/Manifest.hpp"
#include "util/Error.hpp"

#include <string>

namespace orchard::boot {

class ReleaseClient;

// Downloads the channel's bootstrapper to "orchard.new", verifies it, then
// swaps it in with the old binary kept as "orchard.old". Returns true when
// the installed bootstrapper changed.
bool updateBootstrapper(const Layout &layout, const ChannelRelease &release, const std::string &platform,
                        ReleaseClient &client, const CancelToken &cancel);

// Deletes the fallback left by an earlier swap once it is no longer running.
void removeOldBootstrapper(const Layout &layout);

} // namespace orchard::boot
