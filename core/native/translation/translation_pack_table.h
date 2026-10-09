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

#include <cstdint>
#include <span>

struct TranslationPackFile {
  const char *name;
  int64_t size;
  const char *sha256;
};

// One downloadable source->English model.
struct TranslationPack {
  // Unique key and folder name, e.g. "kor-eng" or "kor-eng-high".
  const char *id;
  const char *source;
  // "standard" (tiny, ~20 MB) or "high" (base, ~80 MB).
  const char *quality;
  // Prefix every file name is appended to; empty while the pack has no host.
  const char *baseUrl;
  // Bumps when a pack's files change, so installs from older manifests re-download.
  const char *revision;
  TranslationPackFile files[3];
  const char *credit;
};

std::span<const TranslationPack> translationPackTable();
