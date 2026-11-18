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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef ORCHARD_YOUTUBE_EXTRACTOR_H
#define ORCHARD_YOUTUBE_EXTRACTOR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Extracts the signature program from a YouTube WEB player without building a
// syntax tree. Returns owned JSON (an `error` field on failure) or NULL on OOM.
char *orchard_youtube_extract(const uint8_t *source, size_t length);
void orchard_youtube_extract_free(char *result);

#ifdef __cplusplus
}
#endif

#endif
