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

#include "marian_model.h"
#include "litert_api.h"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <iterator>
#include <limits>

namespace {
// Additive mask value the exports expect; a 1/0 mask is silently ignored and the decoder babbles.
constexpr float kMasked = -1e9f;

template <typename T> T *as(void *handle) { return reinterpret_cast<T *>(handle); }

bool isCache(const std::string &name) { return name.find("_kv_cache_") != std::string::npos; }

void appendUtf8(std::string &out, uint32_t cp) {
  if (cp < 0x80) {
    out += char(cp);
  } else if (cp < 0x800) {
    out += char(0xc0 | (cp >> 6));
    out += char(0x80 | (cp & 0x3f));
  } else if (cp < 0x10000) {
    out += char(0xe0 | (cp >> 12));
    out += char(0x80 | ((cp >> 6) & 0x3f));
    out += char(0x80 | (cp & 0x3f));
  } else {
    out += char(0xf0 | (cp >> 18));
    out += char(0x80 | ((cp >> 12) & 0x3f));
    out += char(0x80 | ((cp >> 6) & 0x3f));
    out += char(0x80 | (cp & 0x3f));
  }
}

// Reads a JSON string body after the opening quote; false on malformed input.
bool readJsonString(std::string_view json, size_t &at, std::string &out) {
  out.clear();
  while (at < json.size()) {
    const char c = json[at++];
    if (c == '"')
      return true;
    if (c != '\\') {
      out += c;
      continue;
    }
    if (at >= json.size())
      return false;
    const char e = json[at++];
    switch (e) {
    case 'b': out += '\b'; break;
    case 'f': out += '\f'; break;
    case 'n': out += '\n'; break;
    case 'r': out += '\r'; break;
    case 't': out += '\t'; break;
    case 'u': {
      auto hex = [&](uint32_t &value) {
        if (at + 4 > json.size())
          return false;
        value = 0;
        for (int i = 0; i < 4; ++i) {
          const char h = json[at++];
          value <<= 4;
          if (h >= '0' && h <= '9') value |= uint32_t(h - '0');
          else if (h >= 'a' && h <= 'f') value |= uint32_t(h - 'a' + 10);
          else if (h >= 'A' && h <= 'F') value |= uint32_t(h - 'A' + 10);
          else return false;
        }
        return true;
      };
      uint32_t cp = 0;
      if (!hex(cp))
        return false;
      if (cp >= 0xd800 && cp < 0xdc00 && json.substr(at, 2) == "\\u") {
        at += 2;
        uint32_t low = 0;
        if (!hex(low))
          return false;
        cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
      }
      appendUtf8(out, cp);
      break;
    }
    default: out += e;
    }
  }
  return false;
}

// vocab.json is one flat {"piece": id} object; a general JSON library would be overkill.
bool readVocab(const std::string &path, std::unordered_map<std::string, int> &vocab) {
  std::ifstream file(path, std::ios::binary);
  const std::string json((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
  vocab.clear();
  size_t at = json.find('{');
  if (at == std::string::npos)
    return false;
  ++at;
  std::string key;
  while (true) {
    at = json.find_first_not_of(" \t\r\n,", at);
    if (at == std::string::npos)
      return false;
    if (json[at] == '}')
      return !vocab.empty();
    if (json[at] != '"' || !readJsonString(json, ++at, key))
      return false;
    at = json.find(':', at);
    if (at == std::string::npos)
      return false;
    char *end = nullptr;
    const long id = std::strtol(json.c_str() + at + 1, &end, 10);
    if (end == json.c_str() + at + 1)
      return false;
    at = size_t(end - json.c_str());
    vocab.emplace(key, int(id));
  }
}

int idOr(const std::unordered_map<std::string, int> &vocab, const char *piece, int fallback) {
  const auto it = vocab.find(piece);
  return it == vocab.end() ? fallback : it->second;
}

// Marian's word boundary marker, U+2581.
constexpr std::string_view kBoundary = "\xe2\x96\x81";
} // namespace

MarianModel::MarianModel() = default;

MarianModel::~MarianModel() { release(); }

void MarianModel::release() {
  if (m_api) {
    for (void *buffer : m_allBuffers)
      m_api->LiteRtDestroyTensorBuffer(as<LiteRtTensorBufferT>(buffer));
    if (m_compiled)
      m_api->LiteRtDestroyCompiledModel(as<LiteRtCompiledModelT>(m_compiled));
    if (m_model)
      m_api->LiteRtDestroyModel(as<LiteRtModelT>(m_model));
    if (m_environment)
      m_api->LiteRtDestroyEnvironment(as<LiteRtEnvironmentT>(m_environment));
  }
  m_allBuffers.clear();
  m_compiled = m_model = m_environment = nullptr;
}

bool MarianModel::load(const LiteRtApi &api, const std::string &directory, int threads, std::string *error) {
  release();
  m_api = &api;
  if (!m_tokenizer.load(directory + "/source.spm")) {
    *error = "Unreadable source.spm";
    return false;
  }
  if (!readVocab(directory + "/vocab.json", m_vocab)) {
    *error = "Unreadable vocab.json";
    return false;
  }
  m_pieces.assign(m_vocab.size(), std::string());
  for (const auto &[piece, id] : m_vocab)
    if (id >= 0 && id < int(m_pieces.size()))
      m_pieces[id] = piece;
  m_eos = idOr(m_vocab, "</s>", 0);
  m_unk = idOr(m_vocab, "<unk>", 1);
  // Marian starts decoding from <pad>.
  m_pad = idOr(m_vocab, "<pad>", int(m_pieces.size()) - 1);

  LiteRtEnvironment environment = nullptr;
  LiteRtModel model = nullptr;
  if (m_api->LiteRtCreateEnvironment(0, nullptr, &environment) != kLiteRtStatusOk ||
      m_api->LiteRtCreateModelFromFile(environment, (directory + "/model.tflite").c_str(), &model) != kLiteRtStatusOk) {
    m_environment = environment;
    *error = "LiteRT could not open model.tflite";
    release();
    return false;
  }
  m_environment = environment;
  m_model = model;

  LiteRtOptions options = nullptr;
  m_api->LiteRtCreateOptions(&options);
  m_api->LiteRtSetOptionsHardwareAccelerators(options, kLiteRtHwAcceleratorCpu);
  // Same TOML the SDK's LrtCpuOptions serializes; spares us its abseil dependency.
  const std::string toml = "num_threads = " + std::to_string(std::max(1, threads)) + "\n";
  char *payload = new char[toml.size() + 1];
  std::memcpy(payload, toml.c_str(), toml.size() + 1);
  LiteRtOpaqueOptions cpu = nullptr;
  if (m_api->LiteRtCreateOpaqueOptions("xnnpack", payload, [](void *p) { delete[] static_cast<char *>(p); }, &cpu) ==
      kLiteRtStatusOk)
    m_api->LiteRtAddOpaqueOptions(options, cpu);
  else
    delete[] payload;
  LiteRtCompiledModel compiled = nullptr;
  const bool compiledOk = m_api->LiteRtCreateCompiledModel(environment, model, options, &compiled) == kLiteRtStatusOk;
  m_api->LiteRtDestroyOptions(options);
  if (!compiledOk) {
    *error = "LiteRT could not compile model.tflite";
    release();
    return false;
  }
  m_compiled = compiled;

  m_shared.clear();
  m_cache[0].clear();
  m_cache[1].clear();
  for (auto *list : {&m_encodeIn, &m_encodeOut, &m_prefillIn, &m_prefillOut, &m_decodeIn[0], &m_decodeIn[1],
                     &m_decodeOut[0], &m_decodeOut[1]})
    list->clear();
  if (!bindSignature("encode", m_encode, error) || !bindSignature("prefill", m_prefill, error) ||
      !bindSignature("decode", m_decode, error) || !makeBuffers(m_encode, 0, m_encodeIn, m_encodeOut, error) ||
      !makeBuffers(m_prefill, 0, m_prefillIn, m_prefillOut, error) ||
      !makeBuffers(m_decode, 0, m_decodeIn[0], m_decodeOut[0], error) ||
      !makeBuffers(m_decode, 1, m_decodeIn[1], m_decodeOut[1], error)) {
    release();
    return false;
  }
  const Storage *ids = storageFor("input_ids", false, 0);
  const Storage *logits = storageFor("logits", true, 0);
  m_length = int(ids->bytes / sizeof(int32_t));
  m_vocabSize = int(logits->bytes / sizeof(float));
  if (m_length < 2 || m_vocabSize < int(m_pieces.size())) {
    *error = "Unexpected model shapes";
    release();
    return false;
  }
  return true;
}

bool MarianModel::bindSignature(const char *key, Signature &out, std::string *error) {
  auto *model = as<LiteRtModelT>(m_model);
  LiteRtParamIndex count = 0;
  m_api->LiteRtGetNumModelSignatures(model, &count);
  for (LiteRtParamIndex i = 0; i < count; ++i) {
    LiteRtSignature signature = nullptr;
    const char *name = nullptr;
    if (m_api->LiteRtGetModelSignature(model, i, &signature) != kLiteRtStatusOk ||
        m_api->LiteRtGetSignatureKey(signature, &name) != kLiteRtStatusOk || std::strcmp(name, key) != 0)
      continue;
    out = Signature{int(i), {}, {}};
    LiteRtParamIndex inputs = 0, outputs = 0;
    m_api->LiteRtGetNumSignatureInputs(signature, &inputs);
    m_api->LiteRtGetNumSignatureOutputs(signature, &outputs);
    const char *tensorName = nullptr;
    for (LiteRtParamIndex k = 0; k < inputs; ++k)
      if (m_api->LiteRtGetSignatureInputName(signature, k, &tensorName) == kLiteRtStatusOk)
        out.inputs.emplace_back(tensorName);
    for (LiteRtParamIndex k = 0; k < outputs; ++k)
      if (m_api->LiteRtGetSignatureOutputName(signature, k, &tensorName) == kLiteRtStatusOk)
        out.outputs.emplace_back(tensorName);
    if (out.inputs.size() == inputs && out.outputs.size() == outputs)
      return true;
  }
  *error = std::string("Model lacks the ") + key + " signature";
  return false;
}

// encode and the decoder steps share their pad mask and encoder states; self caches ping-pong by parity.
// Cross caches pass straight through decode as one tensor, so input and output must be one buffer.
MarianModel::Storage *MarianModel::storageFor(const std::string &name, bool output, int parity) {
  if (isCache(name) && name.rfind("cross_", 0) != 0)
    return &m_cache[output ? 1 - parity : parity][name];
  const std::string shared = name == "output_0" ? "encoder_hidden_states" : name;
  return &m_shared[shared];
}

bool MarianModel::makeBuffers(const Signature &signature, int parity, std::vector<void *> &inputs,
                              std::vector<void *> &outputs, std::string *error) {
  LiteRtSignature handle = nullptr;
  m_api->LiteRtGetModelSignature(as<LiteRtModelT>(m_model), signature.index, &handle);
  for (const bool output : {false, true}) {
    for (const std::string &name : output ? signature.outputs : signature.inputs) {
      LiteRtTensor tensor = nullptr;
      LiteRtRankedTensorType type{};
      const auto found = output ? m_api->LiteRtGetSignatureOutputTensor(handle, name.c_str(), &tensor)
                                : m_api->LiteRtGetSignatureInputTensor(handle, name.c_str(), &tensor);
      if (found != kLiteRtStatusOk || m_api->LiteRtGetRankedTensorType(tensor, &type) != kLiteRtStatusOk) {
        *error = "Unreadable tensor " + name;
        return false;
      }
      if (type.element_type != kLiteRtElementTypeFloat32 && type.element_type != kLiteRtElementTypeInt32) {
        *error = "Unsupported tensor type for " + name;
        return false;
      }
      size_t bytes = 4;
      for (uint32_t d = 0; d < type.layout.rank; ++d)
        bytes *= size_t(std::max(1, type.layout.dimensions[d]));
      Storage *storage = storageFor(name, output, parity);
      if (!storage->data) {
        // Zeroed once; stale cache rows past the current step are masked out anyway.
        storage->raw.assign(bytes + LITERT_HOST_MEMORY_BUFFER_ALIGNMENT, std::byte{0});
        const auto address = reinterpret_cast<uintptr_t>(storage->raw.data());
        const uintptr_t aligned = (address + LITERT_HOST_MEMORY_BUFFER_ALIGNMENT - 1) &
                                  ~uintptr_t(LITERT_HOST_MEMORY_BUFFER_ALIGNMENT - 1);
        storage->data = storage->raw.data() + (aligned - address);
        storage->bytes = bytes;
      } else if (storage->bytes != bytes) {
        *error = "Shape mismatch for " + name;
        return false;
      }
      LiteRtTensorBuffer buffer = nullptr;
      if (m_api->LiteRtCreateTensorBufferFromHostMemory(&type, storage->data, bytes, nullptr, &buffer) !=
          kLiteRtStatusOk) {
        *error = "Could not wrap " + name;
        return false;
      }
      m_allBuffers.push_back(buffer);
      (output ? outputs : inputs).push_back(buffer);
    }
  }
  return true;
}

bool MarianModel::run(const Signature &signature, std::vector<void *> &inputs, std::vector<void *> &outputs) {
  return m_api->LiteRtRunCompiledModel(as<LiteRtCompiledModelT>(m_compiled), signature.index, inputs.size(),
                                       reinterpret_cast<LiteRtTensorBuffer *>(inputs.data()), outputs.size(),
                                       reinterpret_cast<LiteRtTensorBuffer *>(outputs.data())) == kLiteRtStatusOk;
}

std::string MarianModel::translate(std::string_view text, const std::function<bool()> &cancelled) {
  if (!m_compiled)
    return {};
  std::vector<int32_t> ids;
  for (const std::string &piece : m_tokenizer.encode(text)) {
    const auto it = m_vocab.find(piece);
    ids.push_back(it == m_vocab.end() ? m_unk : it->second);
  }
  if (ids.empty())
    return {};
  // Overlong lines lose their tail rather than the whole translation.
  if (int(ids.size()) > m_length - 1)
    ids.resize(m_length - 1);
  ids.push_back(m_eos);
  const int count = int(ids.size());

  auto *inputIds = reinterpret_cast<int32_t *>(m_shared["input_ids"].data);
  auto *inputPos = reinterpret_cast<int32_t *>(m_shared["input_pos"].data);
  auto *padMask = reinterpret_cast<float *>(m_shared["pad_mask"].data);
  for (int i = 0; i < m_length; ++i) {
    inputIds[i] = i < count ? ids[i] : m_pad;
    inputPos[i] = i;
    padMask[i] = i < count ? 0.0f : kMasked;
  }
  if (!run(m_encode, m_encodeIn, m_encodeOut))
    return {};

  auto *token = reinterpret_cast<int32_t *>(m_shared["decoder_input_ids"].data);
  auto *position = reinterpret_cast<int32_t *>(m_shared["decoder_input_pos"].data);
  const auto *logits = reinterpret_cast<const float *>(m_shared["logits"].data);
  // Lyrics rarely triple in length across languages; the cap stops a looping decoder early.
  const int limit = std::min(m_length - 1, count * 3 + 8);
  std::string pieces;
  *token = m_pad;
  for (int step = 0; step < limit; ++step) {
    if (cancelled && cancelled())
      return {};
    *position = step;
    const bool ok = step == 0 ? run(m_prefill, m_prefillIn, m_prefillOut)
                              : run(m_decode, m_decodeIn[step % 2], m_decodeOut[step % 2]);
    if (!ok)
      return {};
    int best = m_eos;
    float bestScore = -std::numeric_limits<float>::infinity();
    for (int v = 0; v < m_vocabSize; ++v) {
      // <pad> is the start token, never an answer.
      if (v != m_pad && logits[v] > bestScore) {
        bestScore = logits[v];
        best = v;
      }
    }
    if (best == m_eos)
      break;
    if (best < int(m_pieces.size()))
      pieces += m_pieces[best];
    *token = best;
  }
  // Boundary markers become spaces, then whitespace runs collapse and the ends trim.
  std::string out;
  bool space = false;
  for (size_t i = 0; i < pieces.size();) {
    const bool boundary = pieces.compare(i, kBoundary.size(), kBoundary) == 0;
    const char c = pieces[i];
    if (boundary || c == ' ' || c == '\t' || c == '\n' || c == '\r') {
      space = !out.empty();
      i += boundary ? kBoundary.size() : 1;
      continue;
    }
    if (space)
      out += ' ';
    space = false;
    out += c;
    ++i;
  }
  return out;
}
