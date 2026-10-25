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
  // Media timescale from mdhd; sample times count in it.
  uint32_t timescale{0};
  // Video tracks: the visual sample entry's size and its avcC body.
  uint16_t width{0};
  uint16_t height{0};
  std::vector<uint8_t> avcC;
};

struct Sample {
  uint8_t *data;
  uint32_t size;
  uint8_t iv[16];
  uint32_t ivSize;
  // Pairs of clear and encrypted byte counts; empty when the whole sample is encrypted.
  std::vector<std::pair<uint32_t, uint32_t>> subsamples;
  // Presentation time in the track's timescale.
  int64_t time{0};
};

constexpr uint32_t fourcc(const char (&code)[5]) {
  return uint32_t(uint8_t(code[0])) << 24 | uint32_t(uint8_t(code[1])) << 16 | uint32_t(uint8_t(code[2])) << 8 |
         uint8_t(code[3]);
}

// Reads the track's protection from an init segment and marks the sample entry clear.
// Returns an error message, or an empty string.
std::string clearInit(Bytes &init, Track &track);

// Hands each encrypted sample of every moof + mdat pair to `decrypt`, which overwrites
// it with clear bytes, then marks the fragment clear.
std::string clearFragment(Bytes &fragment, const Track &track,
                          const std::function<std::string(Sample &)> &decrypt);

// The first key id a Widevine pssh box names, from its v1 key list or its data's key_id
// field. False when it names none.
bool psshKeyId(const Bytes &pssh, uint8_t (&keyId)[16]);

} // namespace cenc_mp4
