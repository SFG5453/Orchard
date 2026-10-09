/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "network/Downloader.hpp"

#include "network/Http.hpp"
#include "platform/Platform.hpp"
#include "security/Hash.hpp"
#include "util/Files.hpp"
#include "util/Log.hpp"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <exception>
#include <memory>
#include <mutex>
#include <set>
#include <thread>
#include <vector>

namespace orchard::boot {

namespace fs = std::filesystem;

namespace {

constexpr int kMaxAttempts = 5;

void backoff(int attempt, const CancelToken &cancel) {
  const int slices = 10 * (1 << std::min(attempt - 1, 4));
  for (int i = 0; i < slices; ++i) {
    cancel.throwIfCancelled();
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
}

} // namespace

Downloader::Downloader(std::string objectsUrl, ObjectStore &store, unsigned parallel)
    : objectsUrl_(std::move(objectsUrl)), store_(store), parallel_(std::max(1u, parallel)) {}

void Downloader::fetch(const std::vector<Chunk> &objects, const CancelToken &cancel, const ProgressFn &progress) {
  std::vector<Chunk> queue;
  std::set<std::string> seen;
  std::uint64_t total = 0;
  for (const Chunk &object : objects) {
    if (seen.insert(object.sha256).second && !store_.contains(object.sha256)) {
      queue.push_back(object);
      total += object.size;
    }
  }
  // Big objects first so a straggler does not end up alone at the tail.
  std::stable_sort(queue.begin(), queue.end(), [](const Chunk &a, const Chunk &b) { return a.size > b.size; });

  std::atomic<std::uint64_t> done{0};
  std::atomic<std::size_t> next{0};
  std::atomic<unsigned> active{0};
  CancelToken stop;
  std::exception_ptr failure;
  std::mutex failureMutex;
  std::vector<std::thread> workers;
  const unsigned count = static_cast<unsigned>(std::min<std::size_t>(parallel_, queue.size()));
  active = count;
  for (unsigned i = 0; i < count; ++i) {
    workers.emplace_back([&] {
      try {
        const auto session = platform::createHttpSession();
        for (std::size_t index; !stop.cancelled() && (index = next++) < queue.size();)
          fetchOne(*session, queue[index], stop, done);
      } catch (...) {
        std::lock_guard lock(failureMutex);
        if (!failure)
          failure = std::current_exception();
        stop.cancel();
      }
      --active;
    });
  }
  while (active > 0) {
    if (cancel.cancelled())
      stop.cancel();
    if (progress)
      progress(done, total);
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
  for (std::thread &worker : workers)
    worker.join();
  if (progress)
    progress(done, total);
  if (cancel.cancelled())
    throw Cancelled();
  if (failure)
    std::rethrow_exception(failure);
}

void Downloader::fetchOne(HttpSession &session, const Chunk &object, const CancelToken &cancel,
                          std::atomic<std::uint64_t> &done) {
  const fs::path part = store_.partPath(object.sha256);
  fs::create_directories(part.parent_path());
  // Bytes of this object currently counted in `done`.
  std::uint64_t credited = 0;
  const auto credit = [&](std::uint64_t now) {
    if (now >= credited)
      done += now - credited;
    else
      done -= credited - now;
    credited = now;
  };

  for (int attempt = 1;; ++attempt) {
    std::error_code error;
    std::uint64_t have = fs::exists(part, error) ? fs::file_size(part, error) : 0;
    if (error || have > object.size) {
      fs::remove(part, error);
      have = 0;
    }
    credit(have);
    try {
      struct Sink : HttpSink {
        std::unique_ptr<std::FILE, int (*)(std::FILE *)> out{nullptr, std::fclose};
        std::unique_ptr<Sha256> hash = std::make_unique<Sha256>();
        std::uint64_t have = 0, size = 0;
        fs::path path;
        std::function<void(std::uint64_t)> credit;
        void onResponse(std::uint64_t offset, std::optional<std::uint64_t> length) override {
          if (offset != have) {
            // The server ignored the Range header; start over.
            out.reset(platform::openFile(path, "wb"));
            hash = std::make_unique<Sha256>();
            have = 0;
            credit(0);
          }
          if (!out)
            throw Error("cannot write " + toUtf8(path));
          if (length && have + *length != size)
            throw NetworkError("object size differs from the manifest");
        }
        void onData(const char *data, std::size_t n) override {
          if (have + n > size)
            throw NetworkError("object is larger than the manifest says");
          if (std::fwrite(data, 1, n, out.get()) != n)
            throw Error("cannot write " + toUtf8(path));
          hash->update(data, n);
          have += n;
          credit(have);
        }
      } sink;
      sink.path = part;
      sink.have = have;
      sink.size = object.size;
      sink.credit = credit;
      if (have > 0) {
        // Re-hash what an earlier run left behind before appending to it.
        std::unique_ptr<std::FILE, int (*)(std::FILE *)> in(platform::openFile(part, "rb"), std::fclose);
        std::vector<char> buffer(1 << 20);
        for (std::size_t got; in && (got = std::fread(buffer.data(), 1, buffer.size(), in.get())) > 0;)
          sink.hash->update(buffer.data(), got);
      }
      sink.out.reset(platform::openFile(part, have > 0 ? "ab" : "wb"));
      if (have < object.size)
        session.get(objectsUrl_ + object.sha256, have, sink, cancel);
      const bool flushed = sink.out && std::fflush(sink.out.get()) == 0;
      sink.out.reset();
      if (!flushed || sink.have != object.size)
        throw NetworkError("object " + object.sha256 + " is truncated");
      if (sink.hash->finishHex() != object.sha256) {
        fs::remove(part, error);
        credit(0);
        throw NetworkError("object " + object.sha256 + " failed hash verification");
      }
      store_.commitPart(object.sha256);
      return;
    } catch (const Cancelled &) {
      throw;
    } catch (const HttpStatusError &e) {
      if (e.status == 416) {
        fs::remove(part, error);
        credit(0);
      } else if (e.status < 500 && e.status != 408 && e.status != 429) {
        throw;
      }
      if (attempt >= kMaxAttempts)
        throw;
      log::warn(std::string("retrying object: ") + e.what());
    } catch (const Error &e) {
      if (attempt >= kMaxAttempts)
        throw;
      log::warn(std::string("retrying object: ") + e.what());
    }
    backoff(attempt, cancel);
  }
}

} // namespace orchard::boot
