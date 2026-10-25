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

#include "cenc_mp4.h"

#include <string>

// The CDM's H.264 decoder takes Annex B, as Chromium's MP4 parser hands it over:
// start codes in place of 4-byte NAL lengths and the avcC parameter sets ahead of
// each IDR. Lengths sit in clear bytes, so only the first subsample grows.
namespace annex_b {

// Fills `out` and `subsamples` for `sample`; returns an error, or an empty string.
std::string convert(const cenc_mp4::Sample &sample, const std::vector<uint8_t> &avcC, cenc_mp4::Bytes &out,
                    std::vector<std::pair<uint32_t, uint32_t>> &subsamples);

} // namespace annex_b
