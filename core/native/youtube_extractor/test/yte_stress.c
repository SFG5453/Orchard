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

// Feeds truncated and byte-mutated copies of a player to the extractor.
// Run under sanitizers; any crash or report is a failure.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "youtube_extractor.h"

static int run(const unsigned char *data, size_t n) {
  char *json = orchard_youtube_extract(data, n);
  if (!json) return 1;
  int ok = strncmp(json, "{\"output\":", 10) == 0;
  orchard_youtube_extract_free(json);
  return ok;
}

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr, "usage: yte_stress <player.js> [iterations]\n");
    return 2;
  }
  FILE *f = fopen(argv[1], "rb");
  if (!f) return 2;
  fseek(f, 0, SEEK_END);
  size_t size = (size_t)ftell(f);
  fseek(f, 0, SEEK_SET);
  unsigned char *data = malloc(size), *copy = malloc(size);
  if (!data || !copy || fread(data, 1, size, f) != size) return 2;
  fclose(f);
  int iterations = argc > 2 ? atoi(argv[2]) : 200;
  unsigned seed = 12345;
  int succeeded = 0;
  static const char noise[] = "(){}[]/`'\"\\;,.=>?:*+-!\n x";
  for (int i = 0; i < iterations; i++) {
    seed = seed * 1103515245u + 12345u;
    size_t cut = (size_t)((seed >> 4) % size);
    succeeded += run(data, cut);
    memcpy(copy, data, size);
    for (int k = 0; k < 1 + i % 2; k++) {
      seed = seed * 1103515245u + 12345u;
      copy[(seed >> 4) % size] = (unsigned char)noise[(seed >> 20) % (sizeof noise - 1)];
    }
    succeeded += run(copy, size);
  }
  printf("%d runs, %d produced output\n", iterations * 2, succeeded);
  free(data);
  free(copy);
  return 0;
}
