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

// orchard-widevine-host <libwidevinecdm.so>
// Decrypts Apple Music's Widevine streams for Orchard over the frames in
// widevine_protocol.h. Runs in its own process so a CDM crash or a Chrome update
// that breaks the interface costs one song, not the app. Exits when stdin closes.

#include "cdm_host.h"
#include "widevine_protocol.h"

#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <map>

using namespace widevine_protocol;
using cenc_mp4::Bytes;

namespace {

bool readExactly(uint8_t *data, size_t size) {
  while (size) {
    const ssize_t got = read(STDIN_FILENO, data, size);
    if (got < 0 && errno == EINTR)
      continue;
    if (got <= 0)
      return false;
    data += got;
    size -= size_t(got);
  }
  return true;
}

bool writeExactly(const uint8_t *data, size_t size) {
  while (size) {
    const ssize_t put = write(STDOUT_FILENO, data, size);
    if (put < 0 && errno == EINTR)
      continue;
    if (put <= 0)
      return false;
    data += put;
    size -= size_t(put);
  }
  return true;
}

uint32_t le32(const uint8_t *p) { return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24; }

void putLe32(uint8_t *p, uint32_t value) {
  for (int i = 0; i < 4; ++i)
    p[i] = uint8_t(value >> (8 * i));
}

bool reply(uint32_t id, Op op, const uint8_t *data, size_t size) {
  uint8_t header[kHeaderBytes];
  putLe32(header, uint32_t(size + 5));
  putLe32(header + 4, id);
  header[8] = op;
  return writeExactly(header, sizeof header) && (!size || writeExactly(data, size));
}

bool replyError(uint32_t id, const std::string &message) {
  return reply(id, Error, reinterpret_cast<const uint8_t *>(message.data()), message.size());
}

} // namespace

int main(int argc, char **argv) {
  if (argc != 2) {
    std::fprintf(stderr, "usage: orchard-widevine-host <libwidevinecdm.so>\n");
    return 2;
  }
  CdmHost host;
  if (const std::string error = host.load(argv[1]); !error.empty()) {
    std::fprintf(stderr, "orchard-widevine-host: %s\n", error.c_str());
    return 1;
  }
  bool writable = true;
  host.renewal = [&writable](uint32_t stream, const Bytes &message) {
    Bytes payload(4 + message.size());
    putLe32(payload.data(), stream);
    std::copy(message.begin(), message.end(), payload.begin() + 4);
    writable = writable && reply(0, Renewal, payload.data(), payload.size());
  };
  std::map<uint32_t, cenc_mp4::Track> tracks;

  while (writable) {
    pollfd input{STDIN_FILENO, POLLIN, 0};
    const int ready = poll(&input, 1, host.nextTimerMs());
    if (ready < 0 && errno != EINTR)
      return 1;
    host.runTimers();
    if (ready <= 0)
      continue;
    uint8_t header[kHeaderBytes];
    if (!readExactly(header, sizeof header))
      return 0;
    const uint32_t length = le32(header);
    const uint32_t id = le32(header + 4);
    const uint8_t op = header[8];
    if (length < 5 || length > kMaxFrameBytes)
      return 1;
    Bytes payload(length - 5);
    if (!readExactly(payload.data(), payload.size()))
      return 0;
    if (payload.size() < 4) {
      writable = replyError(id, "frame has no stream");
      continue;
    }
    const uint32_t stream = le32(payload.data());
    Bytes body(payload.begin() + 4, payload.end());

    switch (op) {
    case Open: {
      Bytes challenge;
      const std::string error = host.open(stream, body, challenge);
      writable = error.empty() ? reply(id, Ok, challenge.data(), challenge.size()) : replyError(id, error);
      break;
    }
    case License: {
      const std::string error = host.update(stream, body);
      writable = error.empty() ? reply(id, Ok, nullptr, 0) : replyError(id, error);
      break;
    }
    case Init: {
      cenc_mp4::Track track;
      const std::string error = cenc_mp4::clearInit(body, track);
      if (error.empty())
        tracks[stream] = track;
      writable = error.empty() ? reply(id, Ok, body.data(), body.size()) : replyError(id, error);
      break;
    }
    case Fragment: {
      const auto track = tracks.find(stream);
      if (track == tracks.end()) {
        writable = replyError(id, "fragment before its init segment");
        break;
      }
      const std::string error = cenc_mp4::clearFragment(
          body, track->second, [&](cenc_mp4::Sample &sample) { return host.decrypt(track->second, sample); });
      writable = error.empty() ? reply(id, Ok, body.data(), body.size()) : replyError(id, error);
      break;
    }
    case Close:
      host.close(stream);
      tracks.erase(stream);
      break;
    default:
      writable = replyError(id, "unknown request");
    }
  }
  return 1;
}
