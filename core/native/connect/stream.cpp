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
#include "connect/stream.h"

#include <algorithm>
#include <cmath>

namespace orchard::connect {
namespace {

bool isPcm(const std::string &codecName) { return codecName == codec::PcmS16 || codecName == codec::PcmF32; }

bool knownCodec(const std::string &codecName) {
  return isPcm(codecName) || codecName == codec::Flac || codecName == codec::Opus || codecName == codec::Source;
}

std::size_t chunkLimit(const Json &meta) {
  const std::size_t frame = frameBytes(meta);
  return frame ? kStreamChunkBytes / frame * frame : kStreamChunkBytes;
}

} // namespace

std::size_t frameBytes(const Json &meta) {
  const std::string name = jsonString(meta, "codec", 16);
  if (!isPcm(name))
    return 0;
  const auto channels = static_cast<std::size_t>(jsonNumber(meta, "channels"));
  return channels * (name == codec::PcmS16 ? 2 : 4);
}

std::string normalizeStream(Json &meta, std::size_t bytes) {
  if (!meta.is_object())
    return code::InvalidStream;
  const std::string name = jsonString(meta, "codec", 16);
  if (!knownCodec(name) || bytes == 0 || bytes > kStreamMaxBytes)
    return code::InvalidStream;
  std::string stream = jsonString(meta, "stream", 64);
  if (stream.empty())
    stream = newId();
  const double timestamp = jsonNumber(meta, "timestamp");
  const double rate = jsonNumber(meta, "sample_rate");
  const double channels = jsonNumber(meta, "channels");
  if (!std::isfinite(timestamp) || timestamp < 0)
    return code::InvalidStream;
  // Encoded bytes describe themselves; PCM needs its shape to be playable at all.
  if (isPcm(name) && (rate < 8000 || rate > 384000 || channels < 1 || channels > 8))
    return code::InvalidStream;
  Json extra = meta.value("meta", Json::object());
  if (!extra.is_object() || extra.dump().size() > 4096)
    return code::InvalidStream;
  meta = {{"stream", stream},
          {"track", jsonString(meta, "track", 128)},
          {"timestamp", timestamp},
          {"codec", name},
          {"sample_rate", static_cast<int>(rate)},
          {"channels", static_cast<int>(channels)},
          {"meta", std::move(extra)}};
  const std::size_t frame = frameBytes(meta);
  if (frame && bytes % frame != 0)
    return code::InvalidStream;
  return {};
}

std::pair<Json, std::string> OutgoingStream::next() {
  const std::size_t size = std::min(chunkLimit(meta), payload.size() - offset);
  Json header = meta;
  header["kind"] = "audio";
  header["sequence"] = sequence;
  header["offset"] = offset;
  header["total"] = payload.size();
  if (const std::size_t frame = frameBytes(meta))
    header["timestamp"] = meta.value("timestamp", 0.0) +
                          static_cast<double>(offset / frame) / meta.value("sample_rate", 48000);
  header["final"] = offset + size == payload.size();
  std::string chunk = payload.substr(offset, size);
  offset += size;
  ++sequence;
  return {std::move(header), std::move(chunk)};
}

std::optional<StreamAssembler::Finished> StreamAssembler::accept(const Json &header, std::string chunk,
                                                                 std::string &stream, std::string &error) {
  stream = jsonString(header, "stream", 64);
  const auto sequence = static_cast<std::uint64_t>(jsonNumber(header, "sequence", -1));
  const auto offset = static_cast<std::size_t>(jsonNumber(header, "offset", -1));
  auto fail = [&]() -> std::optional<Finished> {
    m_open.erase(stream);
    error = code::InvalidStream;
    return std::nullopt;
  };
  if (stream.empty() || chunk.size() > kStreamChunkBytes)
    return fail();
  auto it = m_open.find(stream);
  if (it == m_open.end()) {
    if (sequence != 0 || offset != 0 || m_open.size() >= kStreamMaxOpen)
      return fail();
    Json meta = header;
    const auto total = static_cast<std::size_t>(jsonNumber(header, "total"));
    if (!normalizeStream(meta, total).empty() || meta["stream"] != stream)
      return fail();
    Partial partial{std::move(meta), {}, 0, total};
    partial.bytes.reserve(total);
    it = m_open.emplace(stream, std::move(partial)).first;
  }
  Partial &partial = it->second;
  if (sequence != partial.next || offset != partial.bytes.size() || chunk.size() > partial.total - offset)
    return fail();
  partial.bytes += chunk;
  ++partial.next;
  const bool complete = partial.bytes.size() == partial.total;
  if (jsonBool(header, "final") != complete)
    return fail();
  if (!complete)
    return std::nullopt;
  Finished finished{std::move(partial.meta), std::move(partial.bytes)};
  finished.header["kind"] = "audio";
  finished.header["bytes"] = partial.total;
  m_open.erase(it);
  return finished;
}

std::vector<std::string> StreamAssembler::clear() {
  std::vector<std::string> streams;
  for (const auto &[stream, partial] : m_open)
    streams.push_back(stream);
  m_open.clear();
  return streams;
}

} // namespace orchard::connect
