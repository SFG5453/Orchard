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

#include "cenc_mp4.h"

#include "content_decryption_module.h"

#include <array>
#include <functional>
#include <map>
#include <string>
#include <vector>

// Chrome's Widevine CDM, loaded from the browser's install and driven through
// Chromium's CDM interface 10 the way a browser's media pipeline would. Audio is
// only decrypted. Video licenses refuse plain decryption, so the CDM decodes video
// itself and hands back frames.
class CdmHost final : public cdm::Host_10 {
public:
  struct Picture {
    int64_t timeUs{0};
    uint16_t width{0};
    uint16_t height{0};
    // I420, planes packed without padding.
    cenc_mp4::Bytes pixels;
  };

  ~CdmHost() override;

  std::string load(const std::string &path);
  // Starts a temporary session for `stream`; `challenge` receives the license request.
  std::string open(uint32_t stream, const cenc_mp4::Bytes &pssh, cenc_mp4::Bytes &challenge);
  std::string update(uint32_t stream, const cenc_mp4::Bytes &license);
  std::string decrypt(const cenc_mp4::Track &track, cenc_mp4::Sample &sample);
  void close(uint32_t stream);
  // The usable key the stream's pssh names, or the license's first usable key when the
  // pssh names none it granted. Waits a little for keys held back for output protection.
  std::string keyFor(uint32_t stream, uint8_t (&keyId)[16]);

  // One H.264 decoder for the whole CDM; starting it again replaces the last one.
  std::string startVideo(const cenc_mp4::Track &track);
  // Decodes `sample`, or drains held frames when it is null; `picture` is filled when a
  // frame comes out, with `got` set.
  std::string decodeVideo(const cenc_mp4::Track &track, cenc_mp4::Sample *sample, Picture &picture, bool &got);
  // Drops held frames before a seek.
  void resetVideo();
  void stopVideo();

  // Milliseconds until the CDM's next timer, or -1 without one.
  int nextTimerMs() const;
  void runTimers();

  // A session asked to renew its license.
  std::function<void(uint32_t stream, const cenc_mp4::Bytes &message)> renewal;

private:
  struct Promise {
    bool settled{false};
    std::string error;
  };

  std::string wait(uint32_t promise);
  uint32_t streamFor(const char *session, uint32_t size) const;

  cdm::Buffer *Allocate(uint32_t capacity) override;
  void SetTimer(int64_t delayMs, void *context) override;
  cdm::Time GetCurrentWallTime() override;
  void OnInitialized(bool success) override;
  void OnResolveKeyStatusPromise(uint32_t promise, cdm::KeyStatus status) override;
  void OnResolveNewSessionPromise(uint32_t promise, const char *session, uint32_t size) override;
  void OnResolvePromise(uint32_t promise) override;
  void OnRejectPromise(uint32_t promise, cdm::Exception exception, uint32_t code, const char *message,
                       uint32_t size) override;
  void OnSessionMessage(const char *session, uint32_t sessionSize, cdm::MessageType type, const char *message,
                        uint32_t size) override;
  void OnSessionKeysChange(const char *session, uint32_t sessionSize, bool hasAdditionalUsableKey,
                           const cdm::KeyInformation *keys, uint32_t count) override;
  void OnExpirationChange(const char *, uint32_t, cdm::Time) override {}
  void OnSessionClosed(const char *, uint32_t) override {}
  void SendPlatformChallenge(const char *, uint32_t, const char *, uint32_t) override;
  void EnableOutputProtection(uint32_t mask) override;
  void QueryOutputProtectionStatus() override;
  void OnDeferredInitializationDone(cdm::StreamType, cdm::Status) override {}
  cdm::FileIO *CreateFileIO(cdm::FileIOClient *client) override;
  void RequestStorageId(uint32_t version) override;

  void *m_library{nullptr};
  cdm::ContentDecryptionModule_10 *m_cdm{nullptr};
  bool m_initialized{false};
  bool m_initializeDone{false};
  bool m_videoStarted{false};
  uint32_t m_nextPromise{1};
  std::map<uint32_t, Promise> m_promises;
  // Due time in milliseconds since the epoch.
  std::multimap<double, void *> m_timers;
  std::map<uint32_t, std::string> m_sessions;
  using KeyId = std::array<uint8_t, 16>;
  std::map<std::string, std::vector<std::pair<KeyId, cdm::KeyStatus>>> m_keys;
  std::map<uint32_t, KeyId> m_psshKeys;
  // Set while a session is created: its first message and id land here.
  bool m_creating{false};
  std::string m_createdSession;
  cenc_mp4::Bytes m_createdMessage;
};
