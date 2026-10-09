/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "install/Layout.hpp"

#include "platform/Platform.hpp"

namespace orchard::boot {

std::filesystem::path Layout::bootstrapper() const { return root / fromUtf8(platform::bootstrapperFileName()); }

std::filesystem::path Layout::staging(const std::filesystem::path &final) {
  return final.parent_path() / fromUtf8("." + toUtf8(final.filename()) + ".staging");
}

} // namespace orchard::boot
