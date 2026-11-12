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
#include "autoplay_select.h"
#include "playback_helpers.h"
#include <QDebug>
#include <QJsonArray>
#include <QJsonObject>
#include <QSettings>
#include <utility>

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
    if (url.scheme() != "https" || !(url.host().endsWith(".googlevideo.com") || url.host().endsWith(".c.youtube.com")) ||
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
    applyAutoplay(result.toArray());
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
  if (url.scheme() != "https" || !(url.host().endsWith(".googlevideo.com") || url.host().endsWith(".c.youtube.com")) ||
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
  m_audioEngineOutput.setSurround(m_nextPlayer, stream.value("atmos").toBool());
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
  m_audioEngineOutput.setSurround(m_player, stream.value("atmos").toBool());
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

void PlaybackController::applyAutoplay(const QJsonArray &result) {
  m_autoplayRequest = 0;
  const bool mix = std::exchange(m_mixRequest, false);
  // Drop generated rows that repeat an earlier row before refilling.
  QList<QVariantMap> rows;
  for (const auto &value : m_queue)
    rows.append(value.toMap());
  const auto stale = autoplay::duplicateGeneratedIndices(rows);
  for (auto it = stale.crbegin(); it != stale.crend(); ++it) {
    m_orderedQueue.removeOne(m_queue.at(*it));
    m_queue.removeAt(*it);
    rows.removeAt(*it);
  }
  QList<QVariantMap> existing{m_track, QVariantMap{{"id", m_autoplaySeed}}};
  existing += rows;
  for (const auto &value : m_history)
    existing.append(value.toMap());
  QList<QVariantMap> candidates;
  for (const auto &value : result) {
    auto track = value.toObject().toVariantMap();
    const auto type = track.value("type").toString();
    if (track.value("unplayable").toBool() ||
        (type != "song" && type != "track" && type != "video"))
      continue;
    candidates.append(track);
  }
  const auto picked = autoplay::select(existing, candidates, mix ? 40 : 20);
  const int added = picked.size();
  for (auto track : picked) {
    if (!mix)
      track.insert("autoplayGenerated", true);
    m_queue.append(track);
    m_orderedQueue.append(track);
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
}
