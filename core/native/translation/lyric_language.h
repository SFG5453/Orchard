/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <string>
#include <string_view>
#include <vector>

// Language guesses for UTF-8 lyric lines, as ISO 639-3 codes ("kor", "spa", ...). Empty means unknown.
// Scripts settle CJK, Cyrillic, Greek, and Arabic; Latin lines are scored on function words.
namespace lyric_language {

std::string detectLine(std::string_view text);

struct SongPlan {
  // Main source language. Local plans leave this empty for English or unclear lyrics.
  std::string source;
  // Per input line: true when that line needs translation.
  std::vector<bool> translate;
};

// English lines, adlib-only lines, and lines in other languages stay as written.
SongPlan plan(const std::vector<std::string> &lines);

// Remote models can translate every source language, preserving lines already in the target.
SongPlan remotePlan(const std::vector<std::string> &lines, std::string_view target);
bool supportedTarget(std::string_view code);

// English display name for a code, for the lyrics badge.
std::string displayName(std::string_view code);

} // namespace lyric_language
