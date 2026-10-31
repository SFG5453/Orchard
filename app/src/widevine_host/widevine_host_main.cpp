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

// orchard-widevine-host <libwidevinecdm.so | widevinecdm.dll>
// Decrypts Apple Music's Widevine streams for Orchard over the frames in
// widevine_protocol.h. Runs in its own process so a CDM crash or a Chrome update
// that breaks the interface costs one song, not the app. Exits when stdin closes.

#include "cdm_host.h"
#include "widevine_protocol.h"

#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#include <windows.h>

#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
#else
#include <poll.h>
#include <unistd.h>
#endif

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <map>

using namespace widevine_protocol;
using cenc_mp4::Bytes;

namespace {

#ifdef _WIN32
bool readExactly(uint8_t *data, size_t size) {
  const HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
  while (size) {
    DWORD got = 0;
    if (!ReadFile(input, data, DWORD(std::min<size_t>(size, 1u << 20)), &got, nullptr) || !got)
      return false;
    data += got;
    size -= got;
  }
  return true;
}

bool writeExactly(const uint8_t *data, size_t size) {
  const HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
  while (size) {
    DWORD put = 0;
    if (!WriteFile(output, data, DWORD(std::min<size_t>(size, 1u << 20)), &put, nullptr) || !put)
      return false;
    data += put;
    size -= put;
  }
  return true;
}

#else
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
#endif

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

struct Frame {
  uint32_t id{0};
  uint8_t op{0};
  Bytes payload;
};

enum class Input { Frame, Timeout, Closed, Malformed };

Input readFrame(Frame &frame) {
  uint8_t header[kHeaderBytes];
  if (!readExactly(header, sizeof header))
    return Input::Closed;
  const uint32_t length = le32(header);
  if (length < 5 || length > kMaxFrameBytes)
    return Input::Malformed;
  frame.id = le32(header + 4);
  frame.op = header[8];
  frame.payload.resize(length - 5);
  return readExactly(frame.payload.data(), frame.payload.size()) ? Input::Frame : Input::Closed;
}

#ifdef _WIN32
// Pipes cannot be waited on with a timeout, so a thread blocks on stdin and hands whole frames over.
class FrameQueue {
public:
  FrameQueue() {
    std::thread([this] {
      for (;;) {
        Frame frame;
        const Input input = readFrame(frame);
        std::lock_guard lock(m_mutex);
        if (input != Input::Frame) {
          m_end = input;
          m_changed.notify_one();
          return;
        }
        m_frames.push_back(std::move(frame));
        m_changed.notify_one();
      }
    }).detach();
  }
  Input next(int timeoutMs, Frame &frame) {
    std::unique_lock lock(m_mutex);
    const auto ready = [this] { return !m_frames.empty() || m_end != Input::Frame; };
    if (timeoutMs < 0)
      m_changed.wait(lock, ready);
    else if (!m_changed.wait_for(lock, std::chrono::milliseconds(timeoutMs), ready))
      return Input::Timeout;
    if (m_frames.empty())
      return m_end;
    frame = std::move(m_frames.front());
    m_frames.pop_front();
    return Input::Frame;
  }

private:
  std::mutex m_mutex;
  std::condition_variable m_changed;
  std::deque<Frame> m_frames;
  Input m_end{Input::Frame};
};
#else
Input nextFrame(int timeoutMs, Frame &frame) {
  pollfd input{STDIN_FILENO, POLLIN, 0};
  const int ready = poll(&input, 1, timeoutMs);
  if (ready < 0)
    return errno == EINTR ? Input::Timeout : Input::Malformed;
  return ready ? readFrame(frame) : Input::Timeout;
}
#endif

bool isZero(const uint8_t *keyId) {
  return std::all_of(keyId, keyId + 16, [](uint8_t b) { return !b; });
}

// Descriptions whose tenc names no key use the key the stream's own session licensed.
cenc_mp4::Track withSessionKey(cenc_mp4::Track track) {
  for (cenc_mp4::Track &description : track.descriptions)
    if (isZero(description.keyId))
      std::copy(std::begin(track.keyId), std::end(track.keyId), std::begin(description.keyId));
  return track;
}

} // namespace

int main(int argc, char **argv) {
  if (argc != 2) {
    std::fprintf(stderr, "usage: orchard-widevine-host <widevine cdm library>\n");
    return 2;
  }
#ifdef _WIN32
  _setmode(_fileno(stdin), _O_BINARY);
  _setmode(_fileno(stdout), _O_BINARY);
  FrameQueue queue;
#endif
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
    Frame frame;
#ifdef _WIN32
    const Input input = queue.next(host.nextTimerMs(), frame);
#else
    const Input input = nextFrame(host.nextTimerMs(), frame);
#endif
    host.runTimers();
    if (input == Input::Timeout)
      continue;
    if (input != Input::Frame)
      return input == Input::Closed ? 0 : 1;
    const uint32_t id = frame.id;
    const uint8_t op = frame.op;
    const Bytes &payload = frame.payload;
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
      // Apple's music videos leave tenc's key id zero and name the key only in the playlist's pssh.
      if (error.empty() && isZero(track.keyId))
        host.keyFor(stream, track.keyId);
      if (error.empty())
        tracks[stream] = withSessionKey(track);
      writable = error.empty() ? reply(id, Ok, body.data(), body.size()) : replyError(id, error);
      break;
    }
    case VideoInit: {
      cenc_mp4::Track track;
      std::string error = cenc_mp4::clearInit(body, track);
      if (error.empty() && isZero(track.keyId))
        error = host.keyFor(stream, track.keyId);
      track = withSessionKey(track);
      if (error.empty())
        error = host.startVideo(track);
      if (error.empty())
        tracks[stream] = track;
      writable = error.empty() ? reply(id, Ok, nullptr, 0) : replyError(id, error);
      break;
    }
    case VideoFragment: {
      const auto track = tracks.find(stream);
      if (track == tracks.end()) {
        writable = replyError(id, "fragment before its init segment");
        break;
      }
      CdmHost::Picture picture;
      const auto send = [&] {
        Bytes message(16 + picture.pixels.size());
        putLe32(message.data(), stream);
        putLe32(message.data() + 4, uint32_t(uint64_t(picture.timeUs)));
        putLe32(message.data() + 8, uint32_t(uint64_t(picture.timeUs) >> 32));
        message[12] = uint8_t(picture.width);
        message[13] = uint8_t(picture.width >> 8);
        message[14] = uint8_t(picture.height);
        message[15] = uint8_t(picture.height >> 8);
        std::copy(picture.pixels.begin(), picture.pixels.end(), message.begin() + 16);
        writable = writable && reply(0, Picture, message.data(), message.size());
      };
      std::string error;
      bool got = false;
      if (body.empty()) {
        // Drain until the decoder has nothing left.
        do {
          error = host.decodeVideo(track->second, nullptr, picture, got);
          if (got)
            send();
        } while (got && error.empty());
      } else {
        error = cenc_mp4::clearFragment(body, track->second, [&](cenc_mp4::Sample &sample) {
          std::string failed = host.decodeVideo(track->second, &sample, picture, got);
          if (got)
            send();
          return failed;
        });
      }
      writable = writable && (error.empty() ? reply(id, Ok, nullptr, 0) : replyError(id, error));
      break;
    }
    case VideoReset:
      host.resetVideo();
      writable = reply(id, Ok, nullptr, 0);
      break;
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
      if (const auto track = tracks.find(stream); track != tracks.end() && !track->second.avcC.empty())
        host.stopVideo();
      host.close(stream);
      tracks.erase(stream);
      break;
    default:
      writable = replyError(id, "unknown request");
    }
  }
  return 1;
}
