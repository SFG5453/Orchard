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
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

// Unigram sentencepiece encoder for Marian source.spm files.
// Matches sentencepiece's EncodeAsPieces for unigram models without byte fallback.
class SentencePieceTokenizer {
public:
  bool load(const std::string &path);
  [[nodiscard]] bool loaded() const { return !m_pieces.empty(); }
  // UTF-8 pieces; runs of unknown characters come back merged, like upstream.
  [[nodiscard]] std::vector<std::string> encode(std::string_view text) const;
  // Exposed for parity tests.
  [[nodiscard]] std::string normalize(std::string_view text) const;

private:
  struct Piece {
    float score;
    bool usable;
  };
  // Longest charsmap match at text: replacement and consumed bytes, or 0 bytes.
  [[nodiscard]] std::pair<std::string_view, size_t> charsmapMatch(std::string_view text) const;

  std::unordered_map<std::string, Piece> m_pieces;
  size_t m_maxPieceBytes{0};
  float m_unkScore{0};
  std::vector<uint32_t> m_trie;
  std::string m_normalized;
  bool m_addDummyPrefix{true};
  bool m_removeExtraWhitespace{true};
  bool m_escapeWhitespace{true};
};
