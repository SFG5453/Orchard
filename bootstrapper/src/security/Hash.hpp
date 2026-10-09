/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <limits>
#include <memory>
#include <string>
#include <string_view>

namespace orchard::boot {

class Sha256 {
public:
  Sha256();
  ~Sha256();
  Sha256(const Sha256 &) = delete;
  Sha256 &operator=(const Sha256 &) = delete;

  void update(const void *data, std::size_t size);
  // Lowercase hex digest. The object is spent afterwards.
  std::string finishHex();
  void finish(unsigned char out[32]);

private:
  struct State;
  std::unique_ptr<State> state_;
};

std::string sha256Hex(std::string_view data);
// Hashes [offset, offset + length) of a file; throws if the file is shorter.
std::string sha256File(const std::filesystem::path &path, std::uint64_t offset = 0,
                       std::uint64_t length = std::numeric_limits<std::uint64_t>::max());
bool isSha256Hex(std::string_view text);

} // namespace orchard::boot
