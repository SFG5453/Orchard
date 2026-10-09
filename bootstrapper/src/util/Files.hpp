/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <cstdint>
#include <filesystem>
#include <string>
#include <string_view>

namespace orchard::boot {

namespace fs = std::filesystem;

// std::string paths are UTF-8 everywhere; Windows would otherwise read them as ANSI.
fs::path fromUtf8(std::string_view text);
std::string toUtf8(const fs::path &path);

std::string readFile(const fs::path &path, std::uint64_t limit = 64ull << 20);
// Write beside the target, flush to disk, then rename over it.
void writeFileAtomic(const fs::path &path, std::string_view data);

// Plain stdio copy; std::filesystem::copy_file drags in iostreams and locales.
void copyFile(const fs::path &from, const fs::path &to);

// Validates a '/'-separated manifest path and returns it as a relative path.
// Rejects absolute paths, drive letters, "..", backslashes and Windows device names.
fs::path safeRelativePath(std::string_view text);
bool isSafePathComponent(std::string_view part);
// Component names and versions double as directory names.
bool isSafeName(std::string_view name);

// Throws unless `path` resolves inside `root`, following any existing symlinks.
void ensureInside(const fs::path &root, const fs::path &path);

// Best effort; returns false when something could not be removed.
bool removeTree(const fs::path &path) noexcept;

} // namespace orchard::boot
