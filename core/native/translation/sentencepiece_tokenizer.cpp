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

#include "sentencepiece_tokenizer.h"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <iterator>
#include <limits>

namespace {
constexpr std::string_view kSpace = "\xe2\x96\x81";
// sentencepiece's kUnkPenalty.
constexpr float kUnkPenalty = 10.0f;

// Minimal protobuf reader; .spm files are a ModelProto and we need four fields of it.
struct Reader {
  std::string_view data;
  size_t pos{0};
  bool ok{true};

  bool done() const { return !ok || pos >= data.size(); }
  uint64_t varint() {
    uint64_t value = 0;
    for (int shift = 0; shift < 64 && pos < data.size(); shift += 7) {
      const auto byte = static_cast<uint8_t>(data[pos++]);
      value |= uint64_t(byte & 0x7f) << shift;
      if (!(byte & 0x80))
        return value;
    }
    ok = false;
    return 0;
  }
  std::string_view bytes() {
    const uint64_t length = varint();
    if (!ok || length > data.size() - pos) {
      ok = false;
      return {};
    }
    const std::string_view out = data.substr(pos, length);
    pos += length;
    return out;
  }
  float fixed32() {
    if (data.size() - pos < 4) {
      ok = false;
      return 0;
    }
    float value;
    std::memcpy(&value, data.data() + pos, 4);
    pos += 4;
    return value;
  }
  void skip(uint32_t wire) {
    switch (wire) {
    case 0: varint(); break;
    case 1: pos += 8; break;
    case 2: bytes(); break;
    case 5: pos += 4; break;
    default: ok = false;
    }
    if (pos > data.size())
      ok = false;
  }
};

// Byte length of the UTF-8 character at text, or 0 when malformed.
size_t utf8Length(std::string_view text) {
  const auto lead = static_cast<uint8_t>(text[0]);
  const size_t length = lead < 0x80 ? 1 : (lead >> 5) == 0x6 ? 2 : (lead >> 4) == 0xe ? 3 : (lead >> 3) == 0x1e ? 4 : 0;
  if (length == 0 || length > text.size())
    return 0;
  for (size_t i = 1; i < length; ++i)
    if ((static_cast<uint8_t>(text[i]) & 0xc0) != 0x80)
      return 0;
  return length;
}
} // namespace

bool SentencePieceTokenizer::load(const std::string &path) {
  std::ifstream file(path, std::ios::binary);
  const std::string blob((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  m_pieces.clear();
  if (blob.empty())
    return false;

  float minScore = std::numeric_limits<float>::max();
  Reader model{blob};
  while (!model.done()) {
    const uint64_t key = model.varint();
    const auto field = uint32_t(key >> 3);
    const auto wire = uint32_t(key & 7);
    if (field == 1 && wire == 2) {
      Reader piece{model.bytes()};
      std::string text;
      float score = 0;
      uint64_t type = 1;
      while (!piece.done()) {
        const uint64_t k = piece.varint();
        if (k == (1 << 3 | 2))
          text = std::string(piece.bytes());
        else if (k == (2 << 3 | 5))
          score = piece.fixed32();
        else if (k == (3 << 3 | 0))
          type = piece.varint();
        else
          piece.skip(uint32_t(k & 7));
      }
      // NORMAL and USER_DEFINED pieces join the lattice; CONTROL and UNKNOWN never match text.
      const bool usable = type == 1 || type == 4;
      if (type == 1)
        minScore = std::min(minScore, score);
      if (usable || type == 5) {
        m_maxPieceBytes = std::max(m_maxPieceBytes, text.size());
        m_pieces.emplace(std::move(text), Piece{score, usable});
      }
    } else if (field == 3 && wire == 2) {
      Reader spec{model.bytes()};
      while (!spec.done()) {
        const uint64_t k = spec.varint();
        if (k == (2 << 3 | 2)) {
          const std::string_view map = spec.bytes();
          uint32_t trieBytes = 0;
          if (map.size() < 4)
            continue;
          std::memcpy(&trieBytes, map.data(), 4);
          if (trieBytes > map.size() - 4 || trieBytes % 4)
            continue;
          m_trie.resize(trieBytes / 4);
          std::memcpy(m_trie.data(), map.data() + 4, trieBytes);
          m_normalized = std::string(map.substr(4 + trieBytes));
        } else if (k == (3 << 3 | 0)) {
          m_addDummyPrefix = spec.varint();
        } else if (k == (4 << 3 | 0)) {
          m_removeExtraWhitespace = spec.varint();
        } else if (k == (5 << 3 | 0)) {
          m_escapeWhitespace = spec.varint();
        } else {
          spec.skip(uint32_t(k & 7));
        }
      }
    } else {
      model.skip(wire);
    }
  }
  if (!model.ok || m_pieces.empty()) {
    m_pieces.clear();
    return false;
  }
  m_unkScore = minScore - kUnkPenalty;
  return true;
}

// darts-clone commonPrefixSearch, keeping only the longest hit.
std::pair<std::string_view, size_t> SentencePieceTokenizer::charsmapMatch(std::string_view text) const {
  if (m_trie.empty())
    return {{}, 0};
  const auto offset = [](uint32_t unit) { return (unit >> 10) << ((unit & (1u << 9)) >> 6); };
  size_t node = offset(m_trie[0]);
  size_t bestLength = 0;
  uint32_t bestValue = 0;
  for (size_t i = 0; i < text.size(); ++i) {
    const auto c = static_cast<uint8_t>(text[i]);
    node ^= c;
    if (node >= m_trie.size())
      break;
    const uint32_t unit = m_trie[node];
    if ((unit & ((1u << 31) | 0xff)) != c)
      break;
    node ^= offset(unit);
    if (node >= m_trie.size())
      break;
    if ((unit >> 8) & 1) {
      bestLength = i + 1;
      bestValue = m_trie[node] & ((1u << 31) - 1);
    }
  }
  if (!bestLength || bestValue >= m_normalized.size())
    return {{}, 0};
  // Replacements are NUL-delimited inside the blob.
  return {std::string_view(m_normalized.c_str() + bestValue), bestLength};
}

// Port of sentencepiece::normalizer::Normalizer::Normalize without user-defined symbols.
std::string SentencePieceTokenizer::normalize(std::string_view input) const {
  const auto prefix = [this](std::string_view text) -> std::pair<std::string_view, size_t> {
    if (const auto hit = charsmapMatch(text); hit.second)
      return hit;
    const size_t length = utf8Length(text);
    if (!length)
      return {"\xef\xbf\xbd", 1};
    return {text.substr(0, length), length};
  };

  std::string out;
  if (m_removeExtraWhitespace) {
    while (!input.empty()) {
      const auto p = prefix(input);
      if (p.first != " ")
        break;
      input.remove_prefix(p.second);
    }
  }
  if (input.empty())
    return out;
  const std::string_view space = m_escapeWhitespace ? kSpace : std::string_view(" ");
  if (m_addDummyPrefix)
    out += space;
  bool prevSpace = m_removeExtraWhitespace;
  while (!input.empty()) {
    const auto p = prefix(input);
    std::string_view piece = p.first;
    while (prevSpace && !piece.empty() && piece.front() == ' ')
      piece.remove_prefix(1);
    if (!piece.empty()) {
      for (const char c : piece) {
        if (m_escapeWhitespace && c == ' ')
          out += kSpace;
        else
          out += c;
      }
      prevSpace = piece.back() == ' ';
    }
    input.remove_prefix(p.second);
    prevSpace = prevSpace && m_removeExtraWhitespace;
  }
  if (m_removeExtraWhitespace) {
    while (out.size() >= space.size() && std::string_view(out).substr(out.size() - space.size()) == space)
      out.resize(out.size() - space.size());
  }
  return out;
}

// Viterbi over the unigram lattice; ties keep the shorter piece, as upstream's trie walk does.
std::vector<std::string> SentencePieceTokenizer::encode(std::string_view text) const {
  const std::string normalized = normalize(text);
  const size_t size = normalized.size();
  struct Node {
    float score{0};
    size_t start{0};
    bool reached{false};
    bool unknown{false};
  };
  std::vector<Node> best(size + 1);
  best[0].reached = true;
  for (size_t start = 0; start < size; ++start) {
    if (!best[start].reached)
      continue;
    const float base = best[start].score;
    const size_t charLength = std::max<size_t>(1, utf8Length(std::string_view(normalized).substr(start)));
    bool singleChar = false;
    const size_t limit = std::min(m_maxPieceBytes, size - start);
    for (size_t length = 1; length <= limit; ++length) {
      const auto it = m_pieces.find(normalized.substr(start, length));
      if (it == m_pieces.end() || !it->second.usable)
        continue;
      Node &target = best[start + length];
      const float score = base + it->second.score;
      if (!target.reached || score > target.score)
        target = {score, start, true, false};
      if (length == charLength)
        singleChar = true;
    }
    if (!singleChar) {
      Node &target = best[std::min(size, start + charLength)];
      const float score = base + m_unkScore;
      if (!target.reached || score > target.score)
        target = {score, start, true, true};
    }
  }

  std::vector<std::string> pieces;
  bool lastUnknown = false;
  for (size_t end = size; end > 0;) {
    const Node &node = best[end];
    std::string piece = normalized.substr(node.start, end - node.start);
    if (node.unknown && lastUnknown)
      pieces.back() = piece + pieces.back();
    else
      pieces.push_back(std::move(piece));
    lastUnknown = node.unknown;
    end = node.start;
  }
  std::reverse(pieces.begin(), pieces.end());
  return pieces;
}
