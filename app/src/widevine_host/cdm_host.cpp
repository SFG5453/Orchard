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

#include "cdm_host.h"
#include "annex_b.h"
#include "output_protection.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>

namespace {

constexpr char kKeySystem[] = "com.widevine.alpha";
// License calls settle synchronously in the CDMs seen so far; this bounds a CDM that waits on its own timers.
constexpr double kPromiseTimeoutMs = 10000;
// Keys that need HDCP turn usable once the CDM sees the protection the OS reports.
constexpr double kRestrictedWaitMs = 3000;

#ifdef _WIN32
void *openLibrary(const std::string &path) {
  const int size = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
  std::wstring wide(size_t(std::max(size, 1)), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, wide.data(), size);
  return LoadLibraryW(wide.c_str());
}
void *symbol(void *library, const char *name) {
  return reinterpret_cast<void *>(GetProcAddress(static_cast<HMODULE>(library), name));
}
void closeLibrary(void *library) { FreeLibrary(static_cast<HMODULE>(library)); }
std::string libraryError() { return "error " + std::to_string(GetLastError()); }
#else
void *openLibrary(const std::string &path) { return dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL); }
void *symbol(void *library, const char *name) { return dlsym(library, name); }
void closeLibrary(void *library) { dlclose(library); }
std::string libraryError() { return dlerror(); }
#endif

void sleepMs(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

double nowMs() {
  return std::chrono::duration<double, std::milli>(std::chrono::system_clock::now().time_since_epoch()).count();
}

class HeapBuffer final : public cdm::Buffer {
public:
  explicit HeapBuffer(uint32_t capacity) : m_data(capacity) {}
  void Destroy() override { delete this; }
  uint32_t Capacity() const override { return uint32_t(m_data.size()); }
  uint8_t *Data() override { return m_data.data(); }
  void SetSize(uint32_t size) override { m_size = size; }
  uint32_t Size() const override { return m_size; }

private:
  cenc_mp4::Bytes m_data;
  uint32_t m_size{0};
};

class Block final : public cdm::DecryptedBlock {
public:
  ~Block() override {
    if (m_buffer)
      m_buffer->Destroy();
  }
  void SetDecryptedBuffer(cdm::Buffer *buffer) override { m_buffer = buffer; }
  cdm::Buffer *DecryptedBuffer() override { return m_buffer; }
  void SetTimestamp(int64_t) override {}
  int64_t Timestamp() const override { return 0; }

private:
  cdm::Buffer *m_buffer{nullptr};
};

class VideoFrame final : public cdm::VideoFrame {
public:
  ~VideoFrame() override {
    if (buffer)
      buffer->Destroy();
  }
  void SetFormat(cdm::VideoFormat value) override { format = value; }
  cdm::VideoFormat Format() const override { return format; }
  void SetSize(cdm::Size value) override { size = value; }
  cdm::Size Size() const override { return size; }
  void SetFrameBuffer(cdm::Buffer *value) override { buffer = value; }
  cdm::Buffer *FrameBuffer() override { return buffer; }
  void SetPlaneOffset(cdm::VideoPlane plane, uint32_t value) override { offsets[plane] = value; }
  uint32_t PlaneOffset(cdm::VideoPlane plane) override { return offsets[plane]; }
  void SetStride(cdm::VideoPlane plane, uint32_t value) override { strides[plane] = value; }
  uint32_t Stride(cdm::VideoPlane plane) override { return strides[plane]; }
  void SetTimestamp(int64_t value) override { timestamp = value; }
  int64_t Timestamp() const override { return timestamp; }

  cdm::VideoFormat format{cdm::kUnknownVideoFormat};
  cdm::Size size{};
  cdm::Buffer *buffer{nullptr};
  uint32_t offsets[cdm::kMaxPlanes]{};
  uint32_t strides[cdm::kMaxPlanes]{};
  int64_t timestamp{0};
};

// Temporary sessions need no storage; every open fails so the CDM falls back to memory.
class NoStorage final : public cdm::FileIO {
public:
  explicit NoStorage(cdm::FileIOClient *client) : m_client(client) {}
  void Open(const char *, uint32_t) override { m_client->OnOpenComplete(cdm::FileIOClient::Status::kError); }
  void Read() override { m_client->OnReadComplete(cdm::FileIOClient::Status::kError, nullptr, 0); }
  void Write(const uint8_t *, uint32_t) override { m_client->OnWriteComplete(cdm::FileIOClient::Status::kError); }
  void Close() override { delete this; }

private:
  cdm::FileIOClient *m_client;
};

CdmHost *g_host = nullptr;

void *hostFor(int version, void *) { return version == cdm::Host_10::kVersion ? g_host : nullptr; }

} // namespace

CdmHost::~CdmHost() {
  if (m_cdm)
    m_cdm->Destroy();
  if (m_library) {
    if (auto deinitialize = reinterpret_cast<void (*)()>(symbol(m_library, "DeinitializeCdmModule")))
      deinitialize();
    closeLibrary(m_library);
  }
}

std::string CdmHost::load(const std::string &path) {
  m_library = openLibrary(path);
  if (!m_library)
    return std::string("could not load the CDM: ") + libraryError();
  auto initialize = reinterpret_cast<void (*)()>(symbol(m_library, "InitializeCdmModule_4"));
  auto create = reinterpret_cast<void *(*)(int, const char *, uint32_t, GetCdmHostFunc, void *)>(
      symbol(m_library, "CreateCdmInstance"));
  if (!initialize || !create)
    return "the CDM does not export module version 4";
  initialize();
  g_host = this;
  m_cdm = static_cast<cdm::ContentDecryptionModule_10 *>(
      create(cdm::ContentDecryptionModule_10::kVersion, kKeySystem, sizeof kKeySystem - 1, hostFor, nullptr));
  if (!m_cdm)
    return "the CDM does not offer interface 10";
  m_cdm->Initialize(false, false, false);
  const double deadline = nowMs() + kPromiseTimeoutMs;
  while (!m_initializeDone && nowMs() < deadline) {
    sleepMs(std::max(1, std::min(nextTimerMs(), 20)));
    runTimers();
  }
  return m_initialized ? std::string() : "the CDM did not initialize";
}

std::string CdmHost::wait(uint32_t promise) {
  const double deadline = nowMs() + kPromiseTimeoutMs;
  while (!m_promises[promise].settled && nowMs() < deadline) {
    runTimers();
    if (!m_promises[promise].settled)
      sleepMs(std::max(1, std::min(nextTimerMs(), 20)));
  }
  const Promise done = m_promises[promise];
  m_promises.erase(promise);
  return done.settled ? done.error : "the CDM did not answer";
}

std::string CdmHost::open(uint32_t stream, const cenc_mp4::Bytes &pssh, cenc_mp4::Bytes &challenge) {
  close(stream);
  m_creating = true;
  m_createdSession.clear();
  m_createdMessage.clear();
  const uint32_t promise = m_nextPromise++;
  m_cdm->CreateSessionAndGenerateRequest(promise, cdm::kTemporary, cdm::kCenc, pssh.data(),
                                         uint32_t(pssh.size()));
  std::string error = wait(promise);
  m_creating = false;
  if (error.empty() && (m_createdSession.empty() || m_createdMessage.empty()))
    error = "the CDM produced no license request";
  if (!error.empty())
    return error;
  m_sessions[stream] = m_createdSession;
  if (uint8_t named[16]; cenc_mp4::psshKeyId(pssh, named))
    std::memcpy(m_psshKeys[stream].data(), named, sizeof named);
  challenge = std::move(m_createdMessage);
  return {};
}

std::string CdmHost::update(uint32_t stream, const cenc_mp4::Bytes &license) {
  const auto session = m_sessions.find(stream);
  if (session == m_sessions.end())
    return "no session for this stream";
  const uint32_t promise = m_nextPromise++;
  m_cdm->UpdateSession(promise, session->second.data(), uint32_t(session->second.size()), license.data(),
                       uint32_t(license.size()));
  if (std::string error = wait(promise); !error.empty())
    return error;
  uint8_t keyId[16];
  return keyFor(stream, keyId);
}

std::string CdmHost::decrypt(const cenc_mp4::Track &track, cenc_mp4::Sample &sample) {
  std::vector<cdm::SubsampleEntry> subsamples;
  subsamples.reserve(sample.subsamples.size());
  for (const auto &[clear, cipher] : sample.subsamples)
    subsamples.push_back({clear, cipher});
  cdm::InputBuffer_2 input{};
  input.data = sample.data;
  input.data_size = sample.size;
  const bool cbcs = track.scheme == cenc_mp4::fourcc("cbcs");
  input.encryption_scheme = cbcs ? cdm::EncryptionScheme::kCbcs : cdm::EncryptionScheme::kCenc;
  input.key_id = track.keyId;
  input.key_id_size = sizeof track.keyId;
  input.iv = sample.iv;
  input.iv_size = sample.ivSize;
  input.subsamples = subsamples.empty() ? nullptr : subsamples.data();
  input.num_subsamples = uint32_t(subsamples.size());
  if (cbcs)
    input.pattern = {track.cryptBlocks, track.skipBlocks};
  Block block;
  const cdm::Status status = m_cdm->Decrypt(input, &block);
  if (status == cdm::kNoKey)
    return "no key";
  if (status != cdm::kSuccess || !block.DecryptedBuffer())
    return "decryption failed";
  if (block.DecryptedBuffer()->Size() != sample.size)
    return "the CDM changed a sample's size";
  std::memcpy(sample.data, block.DecryptedBuffer()->Data(), sample.size);
  return {};
}

std::string CdmHost::keyFor(uint32_t stream, uint8_t (&keyId)[16]) {
  const auto session = m_sessions.find(stream);
  if (session == m_sessions.end())
    return "no session for this stream";
  const auto named = m_psshKeys.find(stream);
  const double deadline = nowMs() + kRestrictedWaitMs;
  bool asked = false;
  for (;;) {
    const auto &keys = m_keys[session->second];
    const std::pair<KeyId, cdm::KeyStatus> *pick = nullptr;
    bool restricted = false;
    for (const auto &key : keys) {
      if (named != m_psshKeys.end() && key.first == named->second) {
        pick = &key;
        restricted = key.second == cdm::kOutputRestricted;
        break;
      }
      if (!pick && key.second == cdm::kUsable)
        pick = &key;
      restricted = restricted || key.second == cdm::kOutputRestricted;
    }
    if (pick && pick->second == cdm::kUsable) {
      std::memcpy(keyId, pick->first.data(), sizeof keyId);
      return {};
    }
    if (!restricted)
      return "the license has no usable key";
    if (!asked) {
      output_protection::enable(cdm::kProtectionHDCP);
      asked = true;
    }
    if (nowMs() >= deadline)
      return "the license needs protected (HDCP) display output";
    runTimers();
    sleepMs(20);
  }
}

std::string CdmHost::startVideo(const cenc_mp4::Track &track) {
  stopVideo();
  if (track.avcC.size() < 2 || !track.width || !track.height || !track.timescale)
    return "not an H.264 video track";
  cdm::VideoDecoderConfig_2 config{};
  config.codec = cdm::kCodecH264;
  const uint8_t profile = track.avcC[1];
  config.profile = profile == 66 ? cdm::kH264ProfileBaseline : profile == 77 ? cdm::kH264ProfileMain
                   : profile == 88 ? cdm::kH264ProfileExtended : cdm::kH264ProfileHigh;
  config.format = cdm::kI420;
  config.coded_size = {track.width, track.height};
  std::vector<uint8_t> extra = track.avcC;
  config.extra_data = extra.data();
  config.extra_data_size = uint32_t(extra.size());
  config.encryption_scheme =
      track.scheme == cenc_mp4::fourcc("cbcs") ? cdm::EncryptionScheme::kCbcs : cdm::EncryptionScheme::kCenc;
  if (m_cdm->InitializeVideoDecoder(config) != cdm::kSuccess)
    return "the CDM cannot decode this video";
  m_videoStarted = true;
  return {};
}

std::string CdmHost::decodeVideo(const cenc_mp4::Track &track, cenc_mp4::Sample *sample, Picture &picture,
                                 bool &got) {
  got = false;
  if (!m_videoStarted)
    return "the video decoder is not started";
  cenc_mp4::Bytes data;
  std::vector<std::pair<uint32_t, uint32_t>> ranges;
  std::vector<cdm::SubsampleEntry> subsamples;
  cdm::InputBuffer_2 input{};
  if (sample) {
    if (std::string error = annex_b::convert(*sample, track.avcC, data, ranges); !error.empty())
      return error;
    for (const auto &[clear, cipher] : ranges)
      subsamples.push_back({clear, cipher});
    const bool cbcs = track.scheme == cenc_mp4::fourcc("cbcs");
    input.data = data.data();
    input.data_size = uint32_t(data.size());
    input.encryption_scheme = cbcs ? cdm::EncryptionScheme::kCbcs : cdm::EncryptionScheme::kCenc;
    input.key_id = track.keyId;
    input.key_id_size = sizeof track.keyId;
    input.iv = sample->iv;
    input.iv_size = sample->ivSize;
    input.subsamples = subsamples.data();
    input.num_subsamples = uint32_t(subsamples.size());
    if (cbcs)
      input.pattern = {track.cryptBlocks, track.skipBlocks};
    input.timestamp = sample->time * 1000000 / track.timescale;
  }
  VideoFrame frame;
  const cdm::Status status = m_cdm->DecryptAndDecodeFrame(input, &frame);
  if (status == cdm::kNeedMoreData)
    return {};
  if (status == cdm::kNoKey)
    return "no key";
  if (status != cdm::kSuccess || !frame.buffer)
    return "video decoding failed";
  picture.timeUs = frame.timestamp;
  picture.width = uint16_t(frame.size.width);
  picture.height = uint16_t(frame.size.height);
  picture.pixels.clear();
  picture.pixels.reserve(size_t(picture.width) * picture.height * 3 / 2 + picture.width);
  const uint8_t *pixels = frame.buffer->Data();
  for (int plane = 0; plane < 3; ++plane) {
    const int width = plane ? (picture.width + 1) / 2 : picture.width;
    const int height = plane ? (picture.height + 1) / 2 : picture.height;
    const uint8_t *row = pixels + frame.offsets[plane];
    for (int y = 0; y < height; ++y, row += frame.strides[plane])
      picture.pixels.insert(picture.pixels.end(), row, row + width);
  }
  got = true;
  return {};
}

void CdmHost::resetVideo() {
  if (m_videoStarted)
    m_cdm->ResetDecoder(cdm::kStreamTypeVideo);
}

void CdmHost::stopVideo() {
  if (m_videoStarted)
    m_cdm->DeinitializeDecoder(cdm::kStreamTypeVideo);
  m_videoStarted = false;
}

void CdmHost::close(uint32_t stream) {
  const auto session = m_sessions.find(stream);
  if (session == m_sessions.end())
    return;
  const uint32_t promise = m_nextPromise++;
  m_cdm->CloseSession(promise, session->second.data(), uint32_t(session->second.size()));
  wait(promise);
  m_keys.erase(session->second);
  m_psshKeys.erase(stream);
  m_sessions.erase(session);
}

int CdmHost::nextTimerMs() const {
  if (m_timers.empty())
    return -1;
  return std::max(0, int(m_timers.begin()->first - nowMs()));
}

void CdmHost::runTimers() {
  while (!m_timers.empty() && m_timers.begin()->first <= nowMs()) {
    void *context = m_timers.begin()->second;
    m_timers.erase(m_timers.begin());
    m_cdm->TimerExpired(context);
  }
}

uint32_t CdmHost::streamFor(const char *session, uint32_t size) const {
  const std::string id(session, size);
  for (const auto &[stream, known] : m_sessions)
    if (known == id)
      return stream;
  return 0;
}

cdm::Buffer *CdmHost::Allocate(uint32_t capacity) { return new HeapBuffer(capacity); }

void CdmHost::SetTimer(int64_t delayMs, void *context) { m_timers.emplace(nowMs() + double(delayMs), context); }

cdm::Time CdmHost::GetCurrentWallTime() { return nowMs() / 1000.0; }

void CdmHost::OnInitialized(bool success) {
  m_initialized = success;
  m_initializeDone = true;
}

void CdmHost::OnResolveKeyStatusPromise(uint32_t promise, cdm::KeyStatus) { m_promises[promise].settled = true; }

void CdmHost::OnResolveNewSessionPromise(uint32_t promise, const char *session, uint32_t size) {
  if (m_creating)
    m_createdSession.assign(session, size);
  m_promises[promise].settled = true;
}

void CdmHost::OnResolvePromise(uint32_t promise) { m_promises[promise].settled = true; }

void CdmHost::OnRejectPromise(uint32_t promise, cdm::Exception, uint32_t code, const char *message,
                              uint32_t size) {
  Promise &rejected = m_promises[promise];
  rejected.settled = true;
  rejected.error = size ? std::string(message, size) : "the CDM refused (system code " + std::to_string(code) + ")";
}

void CdmHost::OnSessionMessage(const char *session, uint32_t sessionSize, cdm::MessageType type,
                               const char *message, uint32_t size) {
  const auto *bytes = reinterpret_cast<const uint8_t *>(message);
  if (m_creating && type == cdm::kLicenseRequest) {
    m_createdMessage.assign(bytes, bytes + size);
    return;
  }
  if (const uint32_t stream = streamFor(session, sessionSize); stream && renewal)
    renewal(stream, cenc_mp4::Bytes(bytes, bytes + size));
}

void CdmHost::OnSessionKeysChange(const char *session, uint32_t sessionSize, bool,
                                  const cdm::KeyInformation *keys, uint32_t count) {
  std::vector<std::pair<KeyId, cdm::KeyStatus>> known;
  for (uint32_t i = 0; i < count; ++i) {
    if (keys[i].key_id_size != 16)
      continue;
    KeyId id;
    std::memcpy(id.data(), keys[i].key_id, id.size());
    known.emplace_back(id, keys[i].status);
  }
  m_keys[std::string(session, sessionSize)] = std::move(known);
}

void CdmHost::SendPlatformChallenge(const char *, uint32_t, const char *, uint32_t) {
  m_cdm->OnPlatformChallengeResponse({});
}

void CdmHost::EnableOutputProtection(uint32_t mask) { output_protection::enable(mask); }

void CdmHost::QueryOutputProtectionStatus() {
  uint32_t links = 0;
  uint32_t protection = 0;
  output_protection::query(links, protection);
  m_cdm->OnQueryOutputProtectionStatus(cdm::kQuerySucceeded, links, protection);
}

cdm::FileIO *CdmHost::CreateFileIO(cdm::FileIOClient *client) { return new NoStorage(client); }

void CdmHost::RequestStorageId(uint32_t version) { m_cdm->OnStorageId(version, nullptr, 0); }
