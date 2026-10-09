/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "security/Hash.hpp"

#include "platform/Platform.hpp"
#include "util/Error.hpp"
#include "util/Files.hpp"

#include <mbedtls/sha256.h>

#include <algorithm>
#include <cstdio>
#include <vector>

namespace orchard::boot {

struct Sha256::State {
  mbedtls_sha256_context context;
};

Sha256::Sha256() : state_(std::make_unique<State>()) {
  mbedtls_sha256_init(&state_->context);
  mbedtls_sha256_starts(&state_->context, 0);
}

Sha256::~Sha256() { mbedtls_sha256_free(&state_->context); }

void Sha256::update(const void *data, std::size_t size) {
  mbedtls_sha256_update(&state_->context, static_cast<const unsigned char *>(data), size);
}

void Sha256::finish(unsigned char out[32]) { mbedtls_sha256_finish(&state_->context, out); }

std::string Sha256::finishHex() {
  unsigned char digest[32];
  finish(digest);
  static constexpr char hex[] = "0123456789abcdef";
  std::string out(64, '0');
  for (int i = 0; i < 32; ++i) {
    out[2 * i] = hex[digest[i] >> 4];
    out[2 * i + 1] = hex[digest[i] & 15];
  }
  return out;
}

std::string sha256Hex(std::string_view data) {
  Sha256 hash;
  hash.update(data.data(), data.size());
  return hash.finishHex();
}

std::string sha256File(const std::filesystem::path &path, std::uint64_t offset, std::uint64_t length) {
  std::unique_ptr<std::FILE, int (*)(std::FILE *)> file(platform::openFile(path, "rb"), std::fclose);
  if (!file || !platform::seekFile(file.get(), offset))
    throw Error("cannot read " + toUtf8(path));
  Sha256 hash;
  std::vector<char> buffer(1 << 20);
  std::uint64_t remaining = length;
  while (remaining > 0) {
    const std::size_t want = static_cast<std::size_t>(std::min<std::uint64_t>(buffer.size(), remaining));
    const std::size_t got = std::fread(buffer.data(), 1, want, file.get());
    hash.update(buffer.data(), got);
    if (length != std::numeric_limits<std::uint64_t>::max())
      remaining -= got;
    if (got < want) {
      if (std::ferror(file.get()) || length != std::numeric_limits<std::uint64_t>::max())
        throw Error("short read from " + toUtf8(path));
      break;
    }
  }
  return hash.finishHex();
}

bool isSha256Hex(std::string_view text) {
  return text.size() == 64 && std::all_of(text.begin(), text.end(), [](char c) {
           return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
         });
}

} // namespace orchard::boot
