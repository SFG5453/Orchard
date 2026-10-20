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
#include <functional>
#include <string>
#include <vector>

// Common Encryption (ISO 23001-7) in fragmented MP4, rewritten in place. Boxes that
// describe the encryption are renamed `free` rather than removed, so every byte keeps
// its offset and a decrypted fragment drops into the same range of the same file.
namespace cenc_mp4 {

using Bytes = std::vector<uint8_t>;

struct Track {
  // 'cenc' (AES-CTR) or 'cbcs' (AES-CBC with a pattern).
  uint32_t scheme{0};
  uint8_t keyId[16]{};
  uint8_t perSampleIvSize{0};
  uint8_t constantIv[16]{};
  uint8_t constantIvSize{0};
  uint8_t cryptBlocks{0};
  uint8_t skipBlocks{0};
};

struct Sample {
  uint8_t *data;
  uint32_t size;
  uint8_t iv[16];
  uint32_t ivSize;
  // Pairs of clear and encrypted byte counts; empty when the whole sample is encrypted.
  std::vector<std::pair<uint32_t, uint32_t>> subsamples;
};

constexpr uint32_t fourcc(const char (&code)[5]) {
  return uint32_t(uint8_t(code[0])) << 24 | uint32_t(uint8_t(code[1])) << 16 | uint32_t(uint8_t(code[2])) << 8 |
         uint8_t(code[3]);
}

// Reads the track's protection from an init segment and marks the sample entry clear.
// Returns an error message, or an empty string.
std::string clearInit(Bytes &init, Track &track);

// Hands each encrypted sample of a moof + mdat fragment to `decrypt`, which overwrites
// it with clear bytes, then marks the fragment clear.
std::string clearFragment(Bytes &fragment, const Track &track,
                          const std::function<std::string(Sample &)> &decrypt);

} // namespace cenc_mp4
