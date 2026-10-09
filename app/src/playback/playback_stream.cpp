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

#include "playback_controller.h"
#include "playback_helpers.h"
#include <QDebug>
#include <QJsonArray>
#include <QJsonObject>
#include <QSettings>

void PlaybackController::receiveStream(quint64 id, const QJsonValue &result) {
  if (receiveNonMusicSegments(id, result))
    return;
  if (m_historyRequests.remove(id)) {
    if (historyDebug())
      qInfo() << "YouTube history: request" << id << "accepted";
    return;
  }
  if (m_nextRequest && id == m_nextRequest) {
    m_nextRequest = 0;
    const auto stream = result.toObject();
    const QUrl url(stream.value("url").toString());
    if (url.scheme() != "https" || !url.host().endsWith(".googlevideo.com") ||
        stream.value("contentLength").toDouble() <= 0)
      return;
    openPreparedStream(stream);
    return;
  }
  if (m_albumRequest && id == m_albumRequest) {
    m_albumRequest = 0;
    const QVariantMap found = result.toObject().toVariantMap();
    const QString albumId = found.value(QStringLiteral("albumId")).toString();
    if (albumId.isEmpty() || !m_track.value(QStringLiteral("albumId")).toString().isEmpty() ||
        found.value(QStringLiteral("id")) != m_track.value(QStringLiteral("id")))
      return;
    m_track.insert(QStringLiteral("albumId"), albumId);
    m_track.insert(QStringLiteral("album"), found.value(QStringLiteral("album")));
    emit stateChanged();
    updateSystemMedia();
    savePlaybackState();
    return;
  }
  if (id == m_autoplayRequest && m_autoplayRequest) {
    m_autoplayRequest = 0;
    QSet<QString> known{m_track.value("id").toString(), m_autoplaySeed};
    for (const auto &value : m_queue)
      known.insert(value.toMap().value("id").toString());
    for (const auto &value : m_history)
      known.insert(value.toMap().value("id").toString());
    int added = 0;
    for (const auto &value : result.toArray()) {
      auto track = value.toObject().toVariantMap();
      const auto key = track.value("id").toString();
      const auto type = track.value("type").toString();
      if (key.isEmpty() || known.contains(key) ||
          track.value("unplayable").toBool() ||
          (type != "song" && type != "track" && type != "video"))
        continue;
      known.insert(key);
      track.insert("autoplayGenerated", true);
      m_queue.append(track);
      m_orderedQueue.append(track);
      if (++added == 20)
        break;
    }
    if (!added)
      m_autoplayError = tr("No more recommendations were found.");
    const bool advance = m_waitingForAutoplay && !m_queue.isEmpty();
    m_waitingForAutoplay = false;
    emit queueChanged();
    emit stateChanged();
    updateSystemMedia();
    if (advance)
      next();
    return;
  }
  if (id == m_queueRequest && m_queueRequest) {
    m_queueRequest = 0;
    const auto page = result.toObject();
    const auto tracks = page.value("tracks").toArray().toVariantList();
    m_queuePages.insert(m_queueContinuation);
    m_queueContinuation = page.value("continuation").toString();
    if (m_queuePages.contains(m_queueContinuation)) {
      m_queueContinuation.clear();
      m_queueError = tr("YouTube repeated a playlist page. Play the playlist "
                        "again to reload it.");
    }
    m_playlistLoaded += tracks.size();
    appendPlaylistTracks(m_playlistId, tracks);
    retryQueueLoading();
    ensureAutoplay();
    return;
  }
  if (!m_request || id != m_request)
    return;
  m_request = 0;
  if (qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
    qInfo() << "Playback: stream resolved after" << m_startupTimer.elapsed()
            << "ms";
  const auto stream = result.toObject();
  if (qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
    qInfo() << "Playback resolver stages (ms):"
            << stream.value("timings").toObject();
  const QUrl url(stream.value("url").toString());
  const qint64 length =
      static_cast<qint64>(stream.value("contentLength").toDouble());
  if (url.scheme() != "https" || !url.host().endsWith(".googlevideo.com") ||
      length <= 0) {
    fail(tr("YouTube returned an invalid audio stream."));
    return;
  }
  startResolvedStream(stream);
}

void PlaybackController::openPreparedStream(const QJsonObject &stream) {
  const QUrl localUrl = m_nextProxy->open(stream);
  if (localUrl.isEmpty())
    return;
  m_preparedBitrate = stream.value("bitrate").toInt();
  rememberStreamTracking(m_preparedTrack.value("id").toString(), stream);
  m_nextPlayer->setSource(localUrl);
  m_nextPlayer->pause();
  maybeStartCrossfade();
}

void PlaybackController::startResolvedStream(const QJsonObject &stream) {
  const qint64 length =
      static_cast<qint64>(stream.value("contentLength").toDouble());
  const QUrl localUrl = m_proxy->open(stream);
  if (localUrl.isEmpty()) {
    fail(tr("Unable to start the audio stream."));
    return;
  }
  rememberStreamTracking(m_track.value("id").toString(), stream);
  applyStreamQuality();
  reportQobuzStarted(m_proxy, m_resumePosition);
  m_player->setSource(localUrl);
  if (m_resumePaused) {
    m_player->pause();
    m_loading = false;
    m_resumePaused = false;
  } else {
    m_player->play();
  }
  applyPendingResume();
  int streamBitrate = stream.value(QStringLiteral("bitrate")).toInt();
  if (streamBitrate <= 0 && length > 0 &&
      stream.value(QStringLiteral("durationSeconds")).toDouble() > 0) {
    streamBitrate = static_cast<int>(
        (length * 8) /
        stream.value(QStringLiteral("durationSeconds")).toDouble());
  }
  if (streamBitrate > 0) {
    // Hand the bitrate to the track. Audiophiles rejoice!
    m_track.insert(QStringLiteral("bitrate"), streamBitrate);
  }
  m_restoringPlayback = false;
  savePlaybackState();
  if (m_persistenceEnabled && playing())
    m_persistTimer.start();
  emit stateChanged();
  updateSystemMedia();
}

void PlaybackController::fail(const QString &message) {
  const bool restoring = m_restoringPlayback;
  m_restoringPlayback = false;
  cancelAutoplay();
  reportQobuzEnded(m_proxy, m_player);
  m_proxy->clear();
  m_request = 0;
  m_loading = false;
  m_player->stop();
  m_track.remove(QStringLiteral("bitrate"));
  m_resumePosition = 0.0;
  m_persistTimer.stop();
  if (restoring) {
    m_track.clear();
    m_error.clear();
  } else {
    m_error = message;
  }
  m_lastPublishedPosition = 0.0;
  emit stateChanged();
  updateSystemMedia();
}

