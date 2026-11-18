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

// Prints the extractor JSON for one player file.

#include <stdio.h>
#include <stdlib.h>

#include "youtube_extractor.h"

int main(int argc, char **argv) {
  if (argc != 2) {
    fprintf(stderr, "usage: yte_cli <player.js>\n");
    return 2;
  }
  FILE *f = fopen(argv[1], "rb");
  if (!f) {
    perror(argv[1]);
    return 2;
  }
  fseek(f, 0, SEEK_END);
  long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  unsigned char *data = malloc(size > 0 ? (size_t)size : 1);
  if (!data || fread(data, 1, (size_t)size, f) != (size_t)size) return 2;
  fclose(f);
  char *json = orchard_youtube_extract(data, (size_t)size);
  free(data);
  if (!json) return 3;
  puts(json);
  orchard_youtube_extract_free(json);
  return 0;
}
