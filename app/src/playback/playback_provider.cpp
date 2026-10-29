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

#include "auth/auth_manager.h"
#include "local/local_track.h"
#include "offline/download_manager.h"
#include "apple_music_stream.h"
#include "playback_controller.h"
#include "playback_helpers.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonObject>
#include <QPointer>

namespace {
// Display name of the codec behind a stream's MIME type; empty when unknown.
QString codecName(const QString &mime) {
  const QString m = mime.toLower();
  if (m.contains(QStringLiteral("flac")))
    return QStringLiteral("FLAC");
  if (m.contains(QStringLiteral("opus")))
    return QStringLiteral("Opus");
  if (m.contains(QStringLiteral("mp4a")) || m.contains(QStringLiteral("aac")))
    return QStringLiteral("AAC");
  if (m.contains(QStringLiteral("vorbis")))
    return QStringLiteral("Vorbis");
  if (m.contains(QStringLiteral("mpeg")) || m.contains(QStringLiteral("mp3")))
    return QStringLiteral("MP3");
  return {};
}

} // namespace

void PlaybackController::installRangeReaders() {
  const auto reader = [apple = QPointer<AppleMusicStreams>(m_appleStreams)](
                          const QJsonObject &stream, qint64 start, qint64 end,
                          std::function<void(const QByteArray &, int)> done) {
    if (stream.value(QStringLiteral("provider")).toString() == QStringLiteral("apple") && apple)
      apple->read(stream.value(QStringLiteral("playbackId")).toString(), start, end, std::move(done));
    else
      done({}, 503);
  };
  for (auto &proxy : m_proxies)
    proxy.setRangeReader(reader);
}

void PlaybackController::requestStream(bool refreshStream) {
  if (isAppleTrack(m_track)) {
    startAppleTrack();
    return;
  }
  m_request = m_provider->invoke(
      "playback.resolve",
      QJsonObject{{"track", QJsonObject::fromVariantMap(m_track)},
                  {"session", m_auth->sessionObject()},
                  {"streamQuality", youtubeQuality()},
                  {"refreshStream", refreshStream}});
}

void PlaybackController::requestPreparedStream(bool refreshStream) {
  const QVariantMap track = m_preparedTrack;
  if (isAppleTrack(track)) {
    if (m_appleStreams)
      resolveAppleStream(track, true);
    return;
  }
  if (local::isLocalTrack(track)) {
    openPreparedLocal();
    return;
  }
  if (const QString file = downloadedFileFor(track); !file.isEmpty()) {
    openPreparedFile(file, m_downloads->bitrateFor(track.value(QStringLiteral("id")).toString()));
    return;
  }
  m_nextRequest = m_provider->invoke(
      "playback.resolve",
      QJsonObject{{"track", QJsonObject::fromVariantMap(track)},
                  {"session", m_auth->sessionObject()},
                  {"streamQuality", youtubeQuality()},
                  {"refreshStream", refreshStream}});
}

// The source badge follows whichever proxy feeds the current player.
void PlaybackController::applyStreamQuality() {
  const QJsonObject stream = m_proxy->stream();
  for (const auto key : {"playbackSource", "bitDepth", "sampleRate", "hires", "streamCodec"})
    m_track.remove(QString::fromLatin1(key));
  if (const QString codec = codecName(stream.value(QStringLiteral("mimeType")).toString()); !codec.isEmpty())
    m_track.insert(QStringLiteral("streamCodec"), codec);
}

QString PlaybackController::streamQuality() const {
  return m_streamQuality == QStringLiteral("max") ? QStringLiteral("high") : m_streamQuality;
}

QString PlaybackController::youtubeQuality() const {
  return m_streamQuality == QStringLiteral("max") ? QStringLiteral("high") : m_streamQuality;
}
