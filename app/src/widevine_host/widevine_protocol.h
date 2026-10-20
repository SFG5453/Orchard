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

// Frames between Orchard and orchard-widevine-host over the host's stdin and stdout.
// Every frame is little-endian: u32 length of what follows, u32 request id, u8 op,
// then the payload. Requests address a stream with a leading u32 in the payload.
namespace widevine_protocol {

enum Op : uint8_t {
  // pssh box -> session message (the license challenge)
  Open = 1,
  // license response -> empty
  License = 2,
  // init segment -> the same bytes marked clear; remembers the stream's key id and scheme
  Init = 3,
  // moof + mdat -> the same bytes decrypted and marked clear
  Fragment = 4,
  // -> nothing; closes the stream's session
  Close = 5,

  Ok = 0x80,
  // UTF-8 message
  Error = 0x81,
  // Unrequested (id 0): u32 stream + a renewal challenge to post to the license server
  Renewal = 0x82,
};

constexpr uint32_t kHeaderBytes = 9;
// Fragments of 256 kbps AAC are about 400 KB; anything this large is not a frame.
constexpr uint32_t kMaxFrameBytes = 32u << 20;

} // namespace widevine_protocol
