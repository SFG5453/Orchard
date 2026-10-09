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
#pragma once

#include "connect/protocol.h"

#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <utility>
#include <vector>

// AudioChunk framing: one logical audio stream carried as ordered data frames.
// Frame header: {kind:"audio", stream, track, sequence, offset, total, timestamp, codec,
// sample_rate, channels, final, meta}. PCM chunks hold whole frames, so each chunk's
// timestamp is exact target media time.
namespace orchard::connect {

constexpr std::size_t kStreamChunkBytes = 256 * 1024;
constexpr std::size_t kStreamMaxBytes = 128u * 1024 * 1024;
// Concurrent partial streams one session may have in flight toward us.
constexpr std::size_t kStreamMaxOpen = 4;

namespace codec {
inline constexpr const char *PcmS16 = "pcm_s16";
inline constexpr const char *PcmF32 = "pcm_f32";
inline constexpr const char *Flac = "flac";
inline constexpr const char *Opus = "opus";
// The provider's own encoded bytes, passed through untouched for the receiver to decode.
inline constexpr const char *Source = "source";
} // namespace codec

// Validates stream metadata for `bytes` of payload and fills defaults (a stream id among them).
// Returns an error code, or "" when valid.
std::string normalizeStream(Json &meta, std::size_t bytes);
// Bytes per PCM frame; 0 for encoded codecs.
std::size_t frameBytes(const Json &meta);

struct OutgoingStream {
  std::string sessionId;
  Json meta;
  std::string payload;
  std::size_t offset = 0;
  std::uint64_t sequence = 0;
  bool done() const { return offset >= payload.size(); }
  // Next frame's header and bytes; advances the stream.
  std::pair<Json, std::string> next();
};

class StreamAssembler {
public:
  struct Finished {
    Json header;
    std::string payload;
  };
  // Feeds one "audio" frame; returns the whole stream on its final frame. A frame that
  // breaks order or limits sets `error`, names the stream in `stream` and drops it.
  std::optional<Finished> accept(const Json &header, std::string chunk, std::string &stream, std::string &error);
  // Partial streams, for reporting them failed when the link drops.
  std::vector<std::string> clear();

private:
  struct Partial {
    Json meta;
    std::string bytes;
    std::uint64_t next = 0;
    std::size_t total = 0;
  };
  std::map<std::string, Partial> m_open;
};

} // namespace orchard::connect
