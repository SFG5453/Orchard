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

#include "annex_b.h"

#include <algorithm>

namespace annex_b {
namespace {

constexpr uint8_t kStartCode[4] = {0, 0, 0, 1};

// Start-coded SPS and PPS units from an avcC body.
std::string parameterSets(const std::vector<uint8_t> &avcC, cenc_mp4::Bytes &out) {
  if (avcC.size() < 7)
    return "avcC is truncated";
  if ((avcC[4] & 3) != 3)
    return "NAL lengths are not 4 bytes";
  size_t at = 5;
  for (int list = 0; list < 2; ++list) {
    if (at >= avcC.size())
      return "avcC is truncated";
    const int count = list == 0 ? avcC[at] & 31 : avcC[at];
    ++at;
    for (int i = 0; i < count; ++i) {
      if (at + 2 > avcC.size())
        return "avcC is truncated";
      const size_t length = size_t(avcC[at] << 8 | avcC[at + 1]);
      at += 2;
      if (at + length > avcC.size())
        return "avcC is truncated";
      out.insert(out.end(), kStartCode, kStartCode + 4);
      out.insert(out.end(), avcC.begin() + long(at), avcC.begin() + long(at + length));
      at += length;
    }
  }
  return {};
}

} // namespace

std::string convert(const cenc_mp4::Sample &sample, const std::vector<uint8_t> &avcC, cenc_mp4::Bytes &out,
                    std::vector<std::pair<uint32_t, uint32_t>> &subsamples) {
  // A wholly encrypted sample would hide its NAL lengths.
  if (sample.subsamples.empty())
    return "video sample has no clear bytes";
  out.assign(sample.data, sample.data + sample.size);
  bool idr = false;
  for (size_t at = 0; at + 4 <= out.size();) {
    const size_t length = size_t(out[at]) << 24 | size_t(out[at + 1]) << 16 | size_t(out[at + 2]) << 8 | out[at + 3];
    if (at + 4 + length > out.size() || length == 0)
      return "NAL unit runs past the sample";
    idr = idr || (out[at + 4] & 31) == 5;
    std::copy(kStartCode, kStartCode + 4, out.begin() + long(at));
    at += 4 + length;
  }
  subsamples = sample.subsamples;
  if (!idr)
    return {};
  cenc_mp4::Bytes prefix;
  if (std::string error = parameterSets(avcC, prefix); !error.empty())
    return error;
  out.insert(out.begin(), prefix.begin(), prefix.end());
  subsamples.front().first += uint32_t(prefix.size());
  return {};
}

} // namespace annex_b
