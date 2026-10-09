/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#pragma once

#include "storage/ObjectStore.hpp"
#include "update/Manifest.hpp"
#include "util/Error.hpp"

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace orchard::boot {

using ProgressFn = std::function<void(std::uint64_t done, std::uint64_t total)>;

// Fetches content-addressed objects into an ObjectStore. Each object is
// streamed to "<hash>.part", resumed with a Range request after an
// interruption, and committed only when its size and SHA-256 match.
class Downloader {
public:
  Downloader(std::string objectsUrl, ObjectStore &store, unsigned parallel = 6);

  // Objects already in the store are skipped. Progress runs on the calling
  // thread about ten times a second. Throws on the first object that still
  // fails after retries; finished objects stay for the next attempt.
  void fetch(const std::vector<Chunk> &objects, const CancelToken &cancel, const ProgressFn &progress);

private:
  void fetchOne(class HttpSession &session, const Chunk &object, const CancelToken &cancel,
                std::atomic<std::uint64_t> &done);

  std::string objectsUrl_;
  ObjectStore &store_;
  unsigned parallel_;
};

} // namespace orchard::boot
