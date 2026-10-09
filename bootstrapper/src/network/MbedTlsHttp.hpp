/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "network/Http.hpp"

#include <filesystem>
#include <memory>
#include <vector>

namespace orchard::boot {

// Socket + mbedTLS transport for platforms without a system HTTP stack worth
// using. `trustFiles` are PEM bundles or directories; the first call wins.
std::unique_ptr<HttpSession> createMbedTlsSession(const std::vector<std::filesystem::path> &trustFiles);

} // namespace orchard::boot
