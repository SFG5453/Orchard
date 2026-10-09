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
#include "playback_controller.h"
#include "providers/qobuz/qobuz_service.h"
#include "providers/youtube/youtube_provider.h"

#include <QJsonObject>
#include <QPointer>

namespace {
const QString kQobuz = QStringLiteral("qobuz");

bool isQobuz(const QJsonObject &stream) {
  return stream.value(QStringLiteral("provider")).toString() == kQobuz;
}

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
  // ConnectService reinstalls these without itself before it is destroyed.
  const auto reader = [qobuz = QPointer<QobuzService>(m_qobuz), remote = m_remoteProvider](
                          const QJsonObject &stream, qint64 start, qint64 end,
                          std::function<void(const QByteArray &, int)> done) {
    if (stream.value(QStringLiteral("remote")).toBool()) {
      if (!remote) {
        done({}, 503);
        return;
      }
      remote->readRange(kQobuz, stream.value(QStringLiteral("playbackId")).toString(), start, end,
                        [done = std::move(done)](const QByteArray &bytes, const QString &error) {
                          done(error.isEmpty() ? bytes : QByteArray(), error.isEmpty() ? 0 : 502);
                        });
      return;
    }
    if (!qobuz) {
      done({}, 503);
      return;
    }
    qobuz->readRange(stream.value(QStringLiteral("playbackId")).toString(), start, end,
                     [done = std::move(done)](const QByteArray &bytes, const QString &error) {
                       done(error.isEmpty() ? bytes : QByteArray(), error.isEmpty() ? 0 : 502);
                     });
  };
  for (auto &proxy : m_proxies)
    proxy.setRangeReader(reader);
}

void PlaybackController::attachQobuz() {
  installRangeReaders();
  if (!m_qobuz)
    return;

  m_qobuz->setEnabled(m_streamQuality == QStringLiteral("max"));
  m_qobuzWasActive = m_qobuz->active();
  m_qobuzWasConnected = m_qobuz->connected();
  // Adaptive mix analyses YouTube audio; it sits out while Qobuz supplies the songs.
  m_adaptiveMix.setAvailable(!m_qobuzWasActive);
  // Signing in is asking for MAX; signing out can't keep it.
  connect(m_qobuz, &QobuzService::accountConnected, this,
          [this] { setStreamQuality(QStringLiteral("max")); });
  connect(m_qobuz, &QobuzService::accountDisconnected, this, [this] {
    if (m_streamQuality == QStringLiteral("max"))
      setStreamQuality(QStringLiteral("high"));
  });
  connect(m_qobuz, &QobuzService::changed, this, [this] {
    const bool active = m_qobuz->active();
    m_adaptiveMix.setAvailable(!active);
    if (m_qobuz->connected() != m_qobuzWasConnected) {
      m_qobuzWasConnected = m_qobuz->connected();
      // A restored session turns a saved MAX back on.
      emit streamQualityChanged();
    }
    if (active == m_qobuzWasActive)
      return;
    m_qobuzWasActive = active;
    // The preloaded next song came from the other source; fetch it again.
    clearPreparedTrack();
    prepareNextTrack();
  });
}

bool PlaybackController::qobuzEligible(const QVariantMap &track) const {
  const QString type = track.value(QStringLiteral("type")).toString();
  // Music videos carry their own intros and skits; the album cut would not match.
  return qobuzAvailable() && !local::isLocalTrack(track) &&
         (type == QStringLiteral("song") || type == QStringLiteral("track")) &&
         !track.value(QStringLiteral("isUpload")).toBool() &&
         !track.value(QStringLiteral("title")).toString().trimmed().isEmpty();
}

QJsonObject PlaybackController::qobuzStream(const QVariantMap &track, const QJsonValue &result) const {
  const QJsonObject source = result.toObject().value(QStringLiteral("source")).toObject();
  const QJsonObject match = result.toObject().value(QStringLiteral("match")).toObject();
  const QString playbackId = source.value(QStringLiteral("playbackId")).toString();
  const double totalBytes = source.value(QStringLiteral("totalBytes")).toDouble();
  if (playbackId.isEmpty() || totalBytes <= 0)
    return {};
  const double duration = source.value(QStringLiteral("durationSeconds")).toDouble();
  const int bitDepth = source.value(QStringLiteral("bitDepth")).toInt();
  const int sampleRate = source.value(QStringLiteral("sampleRate")).toInt();
  return {{QStringLiteral("provider"), kQobuz},
          {QStringLiteral("playbackId"), playbackId},
          {QStringLiteral("contentLength"), totalBytes},
          {QStringLiteral("mimeType"), QStringLiteral("audio/flac")},
          {QStringLiteral("durationSeconds"), duration},
          {QStringLiteral("bitrate"), duration > 0 ? static_cast<int>(totalBytes * 8 / duration) : 0},
          {QStringLiteral("bitDepth"), bitDepth},
          {QStringLiteral("sampleRate"), sampleRate},
          {QStringLiteral("hires"), match.value(QStringLiteral("hires")).toBool() || bitDepth > 16 ||
                                        sampleRate > 48000},
          // Likes and history still address the catalog's YouTube recording.
          {QStringLiteral("youtubeVideoId"), track.value(QStringLiteral("id")).toString()},
          {QStringLiteral("remote"), !localQobuzActive()}};
}

bool PlaybackController::localQobuzActive() const { return m_qobuz && m_qobuz->active(); }

bool PlaybackController::qobuzAvailable() const {
  if (localQobuzActive())
    return true;
  // Signed in only on a Connect peer: borrow its session when MAX is the saved choice.
  return !(m_qobuz && m_qobuz->connected()) && m_streamQuality == QStringLiteral("max") && m_remoteProvider &&
         m_remoteProvider->available(kQobuz);
}

void PlaybackController::resolveQobuz(const QVariantMap &track,
                                      std::function<void(const QJsonValue &, const QString &)> done) {
  if (localQobuzActive())
    m_qobuz->resolveTrack(track, std::move(done));
  else if (m_remoteProvider)
    m_remoteProvider->resolveTrack(kQobuz, track, std::move(done));
  else
    done(QJsonValue(), QStringLiteral("provider_unavailable"));
}

void PlaybackController::requestStream(bool refreshStream, bool tryQobuz) {
  if (!refreshStream && tryQobuz && qobuzEligible(m_track)) {
    const quint64 generation = m_generation;
    const QVariantMap track = m_track;
    resolveQobuz(track, [this, generation, track](const QJsonValue &result, const QString &) {
      if (generation != m_generation)
        return;
      const QJsonObject stream = qobuzStream(track, result);
      if (!stream.isEmpty()) {
        startResolvedStream(stream);
        return;
      }
      // No confident match: the YouTube recording is still the right song.
      requestStream(false, false);
    });
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
  if (local::isLocalTrack(track)) {
    openPreparedLocal();
    return;
  }
  if (const QString file = downloadedFileFor(track); !file.isEmpty()) {
    openPreparedFile(file, m_downloads->bitrateFor(track.value(QStringLiteral("id")).toString()));
    return;
  }
  if (!refreshStream && qobuzEligible(track)) {
    const quint64 token = ++m_preparedQobuzToken;
    resolveQobuz(track, [this, token, track](const QJsonValue &result, const QString &) {
      if (token != m_preparedQobuzToken || m_preparedTrack.value("id") != track.value("id"))
        return;
      const QJsonObject stream = qobuzStream(track, result);
      if (!stream.isEmpty())
        openPreparedStream(stream);
      else
        m_nextRequest = m_provider->invoke(
            "playback.resolve",
            QJsonObject{{"track", QJsonObject::fromVariantMap(track)},
                        {"session", m_auth->sessionObject()},
                        {"streamQuality", youtubeQuality()}});
    });
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
  if (!isQobuz(stream))
    return;
  m_track.insert(QStringLiteral("playbackSource"), kQobuz);
  m_track.insert(QStringLiteral("bitDepth"), stream.value(QStringLiteral("bitDepth")).toInt());
  m_track.insert(QStringLiteral("sampleRate"), stream.value(QStringLiteral("sampleRate")).toInt());
  m_track.insert(QStringLiteral("hires"), stream.value(QStringLiteral("hires")).toBool());
}

void PlaybackController::reportQobuzStarted(AudioStreamProxy *proxy, double position) {
  const QJsonObject stream = proxy->stream();
  if (!isQobuz(stream))
    return;
  const QString playbackId = stream.value(QStringLiteral("playbackId")).toString();
  if (stream.value(QStringLiteral("remote")).toBool()) {
    if (m_remoteProvider)
      m_remoteProvider->report(kQobuz, playbackId, true, position);
  } else if (m_qobuz) {
    m_qobuz->playbackStarted(playbackId, position);
  }
}

void PlaybackController::reportQobuzEnded(AudioStreamProxy *proxy, QMediaPlayer *player) {
  const QJsonObject stream = proxy->stream();
  if (!isQobuz(stream))
    return;
  const QString playbackId = stream.value(QStringLiteral("playbackId")).toString();
  const double position = player ? player->position() / 1000.0 : 0.0;
  if (stream.value(QStringLiteral("remote")).toBool()) {
    if (m_remoteProvider)
      m_remoteProvider->report(kQobuz, playbackId, false, position);
  } else if (m_qobuz) {
    m_qobuz->playbackEnded(playbackId, position);
  }
}

QString PlaybackController::streamQuality() const {
  if (m_streamQuality == QStringLiteral("max") && !qobuzReachable())
    return QStringLiteral("high");
  return m_streamQuality;
}

bool PlaybackController::qobuzReachable() const {
  return (m_qobuz && m_qobuz->connected()) || (m_remoteProvider && m_remoteProvider->available(kQobuz));
}

void PlaybackController::remoteProviderChanged() {
  const bool reachable = qobuzReachable();
  if (reachable == m_qobuzWasReachable)
    return;
  m_qobuzWasReachable = reachable;
  emit streamQualityChanged();
  // The preloaded next song came from the other source; fetch it again.
  clearPreparedTrack();
}

QString PlaybackController::youtubeQuality() const {
  return m_streamQuality == QStringLiteral("max") ? QStringLiteral("high") : m_streamQuality;
}
