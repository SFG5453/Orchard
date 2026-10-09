/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "storage/ObjectStore.hpp"

#include "platform/Platform.hpp"
#include "security/Hash.hpp"
#include "util/Error.hpp"
#include "util/Files.hpp"

#include <cstdio>
#include <memory>
#include <vector>

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

using FilePtr = std::unique_ptr<std::FILE, int (*)(std::FILE *)>;

FilePtr open(const fs::path &path, const char *mode) { return FilePtr(platform::openFile(path, mode), std::fclose); }

// Streams `size` bytes from `in` to `out`, feeding `hash`. False on short read or write error.
bool copyBytes(std::FILE *in, std::FILE *out, std::uint64_t size, Sha256 &hash) {
  std::vector<char> buffer(1 << 20);
  while (size > 0) {
    const std::size_t want = static_cast<std::size_t>(std::min<std::uint64_t>(buffer.size(), size));
    if (std::fread(buffer.data(), 1, want, in) != want)
      return false;
    hash.update(buffer.data(), want);
    if (std::fwrite(buffer.data(), 1, want, out) != want)
      return false;
    size -= want;
  }
  return true;
}

} // namespace

ObjectStore::ObjectStore(fs::path dir) : dir_(std::move(dir)) {}

fs::path ObjectStore::objectPath(std::string_view sha256) const {
  return dir_ / fromUtf8(sha256.substr(0, 2)) / fromUtf8(sha256);
}

fs::path ObjectStore::partPath(std::string_view sha256) const {
  fs::path path = objectPath(sha256);
  path += ".part";
  return path;
}

bool ObjectStore::contains(std::string_view sha256) const {
  std::error_code error;
  return fs::is_regular_file(objectPath(sha256), error);
}

void ObjectStore::commitPart(std::string_view sha256) { fs::rename(partPath(sha256), objectPath(sha256)); }

bool ObjectStore::importRange(const Chunk &chunk, const fs::path &source, std::uint64_t offset) {
  std::error_code error;
  if (fs::file_size(source, error) < offset + chunk.size || error)
    return false;
  fs::create_directories(objectPath(chunk.sha256).parent_path());
  const fs::path part = partPath(chunk.sha256);
  bool ok = false;
  {
    FilePtr in = open(source, "rb");
    FilePtr out = open(part, "wb");
    Sha256 hash;
    ok = in && out && platform::seekFile(in.get(), offset) && copyBytes(in.get(), out.get(), chunk.size, hash) &&
         std::fflush(out.get()) == 0 && hash.finishHex() == chunk.sha256;
  }
  if (!ok) {
    fs::remove(part, error);
    return false;
  }
  commitPart(chunk.sha256);
  return true;
}

void ObjectStore::clear() { removeTree(dir_); }

void assembleFile(const FileEntry &entry, const fs::path &target, const ObjectStore &store) {
  fs::create_directories(target.parent_path());
  bool ok = true;
  {
    FilePtr out = open(target, "wb");
    if (!out)
      throw Error("cannot create " + toUtf8(target));
    Sha256 hash;
    for (const Chunk &chunk : entry.chunks) {
      FilePtr in = open(store.objectPath(chunk.sha256), "rb");
      std::error_code error;
      if (!in || fs::file_size(store.objectPath(chunk.sha256), error) != chunk.size ||
          !copyBytes(in.get(), out.get(), chunk.size, hash)) {
        ok = false;
        break;
      }
    }
    ok = ok && std::fflush(out.get()) == 0 && hash.finishHex() == entry.sha256;
  }
  if (!ok) {
    std::error_code error;
    fs::remove(target, error);
    throw Error("assembled " + entry.path + " does not match its manifest hash");
  }
  if (entry.executable)
    platform::makeExecutable(target);
}

bool fileMatches(const FileEntry &entry, const fs::path &path) {
  std::error_code error;
  if (!fs::is_regular_file(path, error) || fs::file_size(path, error) != entry.size || error)
    return false;
  try {
    return sha256File(path) == entry.sha256;
  } catch (const Error &) {
    return false;
  }
}

} // namespace orchard::boot
