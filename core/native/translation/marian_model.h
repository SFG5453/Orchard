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

#include "sentencepiece_tokenizer.h"

#include <functional>
#include <map>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

struct LiteRtApi;

// One Marian language pair exported as LiteRT encode/prefill/decode signatures.
// Pack layout: model.tflite, source.spm, vocab.json. Not thread-safe; use from one thread.
class MarianModel {
public:
  MarianModel();
  ~MarianModel();
  MarianModel(const MarianModel &) = delete;
  MarianModel &operator=(const MarianModel &) = delete;

  // directory is a native-encoded path; api comes from LiteRtApi::get.
  bool load(const LiteRtApi &api, const std::string &directory, int threads, std::string *error);
  // Greedy decode of one UTF-8 line. Returns an empty string when cancelled or on a runtime error.
  std::string translate(std::string_view text, const std::function<bool()> &cancelled = {});

private:
  struct Storage {
    std::vector<std::byte> raw;
    std::byte *data{nullptr};
    size_t bytes{0};
  };
  struct Signature {
    int index{-1};
    std::vector<std::string> inputs;
    std::vector<std::string> outputs;
  };
  bool bindSignature(const char *key, Signature &out, std::string *error);
  Storage *storageFor(const std::string &name, bool output, int parity);
  bool makeBuffers(const Signature &signature, int parity, std::vector<void *> &inputs,
                   std::vector<void *> &outputs, std::string *error);
  bool run(const Signature &signature, std::vector<void *> &inputs, std::vector<void *> &outputs);
  void release();

  const LiteRtApi *m_api{nullptr};
  void *m_environment{nullptr};
  void *m_model{nullptr};
  void *m_compiled{nullptr};
  SentencePieceTokenizer m_tokenizer;
  std::unordered_map<std::string, int> m_vocab;
  std::vector<std::string> m_pieces;
  int m_eos{0};
  int m_pad{0};
  int m_unk{1};
  int m_length{0};
  int m_vocabSize{0};
  Signature m_encode, m_prefill, m_decode;
  std::map<std::string, Storage> m_shared;
  std::map<std::string, Storage> m_cache[2];
  // Buffers wrap the storage above; [parity] picks which cache set is read.
  std::vector<void *> m_encodeIn, m_encodeOut, m_prefillIn, m_prefillOut;
  std::vector<void *> m_decodeIn[2], m_decodeOut[2];
  std::vector<void *> m_allBuffers;
};
