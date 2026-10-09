/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <filesystem>
#include <string_view>

namespace orchard::boot::log {

// Appends to `file`, rotating once it passes 1 MiB. Safe to call from any thread.
void open(const std::filesystem::path &file);
void setEcho(bool echo);

void write(std::string_view level, std::string_view message);
inline void info(std::string_view message) { write("info", message); }
inline void warn(std::string_view message) { write("warn", message); }
inline void error(std::string_view message) { write("error", message); }

} // namespace orchard::boot::log
