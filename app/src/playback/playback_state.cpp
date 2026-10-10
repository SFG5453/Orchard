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
#include "playback_controller.h"
#include "playback_helpers.h"
#include "providers/youtube/youtube_provider.h"
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSettings>
#include <algorithm>

namespace {
constexpr auto kPageKey = "playback/persistedPage";
// Detail items carry whole catalog payloads; refuse oversized snapshots.
constexpr qsizetype kMaxPageBytes = 64 * 1024;
}

QVariantMap PlaybackController::sanitizeTrack(const QVariantMap &track) {
  if (track.value(QStringLiteral("id")).toString().trimmed().isEmpty())
    return {};
  QVariantMap sanitized = track;
  // Strip transient stream tokens. Nobody likes an expired URL masquerading
  // as a fresh audio track.
  const auto ephemeralKeys = {"bitrate",
                              "streamUrl",
                              "audioStreamUrl",
                              "playbackFallbackTried",
                              "streamRefreshTried",
                              "failedAudioItags",
                              "failedAudioMimeTypes",
                              "failedVideoItags",
                              "authenticatedPlayback",
                              "itag",
                              "audioItag",
                              "mimeType",
                              "playbackSource",
                              "bitDepth",
                              "sampleRate",
                              "hires",
                              "streamCodec"};
  for (const auto &key : ephemeralKeys)
    sanitized.remove(QString::fromLatin1(key));
  return sanitized;
}

QVariantList PlaybackController::sanitizeTrackList(const QVariantList &tracks, int maxItems) {
  // Capping at 2500 tracks. If your queue is longer than that... wtf???
  QVariantList sanitized;
  sanitized.reserve(std::min<qsizetype>(tracks.size(), maxItems));
  for (const auto &item : tracks) {
    if (sanitized.size() >= maxItems)
      break;
    const QVariantMap track = sanitizeTrack(item.toMap());
    if (!track.isEmpty())
      sanitized.append(track);
  }
  return sanitized;
}

void PlaybackController::applyPendingResume() {
  if (!m_player || m_resumePosition <= 0.0 || !m_player->isSeekable())
    return;
  if (m_player->mediaStatus() == QMediaPlayer::LoadingMedia ||
      m_player->mediaStatus() == QMediaPlayer::NoMedia)
    return;

  // Qt's multimedia backend refuses to seek before media has actually loaded,
  // so we patiently wait for LoadedMedia instead of shouting setPosition() into
  // the void.
  const double maxDuration = duration();
  const double targetSec = (maxDuration > 0.0)
                               ? qMin(m_resumePosition, maxDuration)
                               : m_resumePosition;
  const qint64 targetMs = static_cast<qint64>(targetSec * 1000);
  m_resumePosition = 0.0;
  m_player->setPosition(targetMs);
  m_lastPublishedPosition = targetSec;
  emit stateChanged();
  updateSystemMedia();
}

void PlaybackController::savePlaybackState() {
  if (!m_persistenceEnabled || !m_prepared || m_restoringPlayback)
    return;

  QSettings settings;
  if (!m_track.isEmpty()) {
    const QVariantMap sanitized = sanitizeTrack(m_track);
    settings.setValue(QStringLiteral("playback/persistedTrack"), sanitized);
    settings.setValue(QStringLiteral("playback/persistedPosition"), position());
    settings.setValue(QStringLiteral("playback/persistedDuration"), duration());
    settings.setValue(QStringLiteral("playback/lastSong"), sanitized);
  }

  // Shuffling reshuffles the deck, but we keep the original deck in
  // m_orderedQueue so turning off shuffle doesn't leave the universe in
  // permanent entropy.
  settings.setValue(QStringLiteral("playback/persistedQueue"),
                    sanitizeTrackList(m_queue, 2500));
  settings.setValue(QStringLiteral("playback/persistedHistory"),
                    sanitizeTrackList(m_history, 50));
  settings.setValue(QStringLiteral("playback/persistedShuffleSource"),
                    sanitizeTrackList(m_orderedQueue, 2500));
  settings.setValue(QStringLiteral("playback/repeatMode"), m_repeatMode);
  settings.setValue(QStringLiteral("playback/shuffleEnabled"),
                    m_shuffleEnabled);
}

void PlaybackController::savePage(const QVariantMap &page) {
  if (!m_persistenceEnabled)
    return;
  const QByteArray json =
      QJsonDocument(QJsonObject::fromVariantMap(page)).toJson(QJsonDocument::Compact);
  if (json.size() > kMaxPageBytes)
    return;
  QSettings().setValue(QLatin1String(kPageKey), json);
}

QVariantMap PlaybackController::restoredPage() const {
  if (!m_persistenceEnabled)
    return {};
  const QByteArray json =
      QSettings().value(QLatin1String(kPageKey)).toByteArray();
  return QJsonDocument::fromJson(json).object().toVariantMap();
}

void PlaybackController::restorePlayback() {
  if (!m_persistenceEnabled || !m_auth->isSignedIn())
    return;

  QSettings settings;
  const QVariantList savedQueue =
      settings.value(QStringLiteral("playback/persistedQueue")).toList();
  const QVariantList savedHistory =
      settings.value(QStringLiteral("playback/persistedHistory")).toList();
  const QVariantList savedShuffleSource =
      settings.value(QStringLiteral("playback/persistedShuffleSource"))
          .toList();

  if (!savedQueue.isEmpty() && m_queue.isEmpty()) {
    m_queue = sanitizeTrackList(savedQueue, 2500);
    emit queueChanged();
  }
  if (!savedHistory.isEmpty() && m_history.isEmpty()) {
    m_history = sanitizeTrackList(savedHistory, 50);
    emit historyChanged();
  }
  if (!savedShuffleSource.isEmpty() && m_orderedQueue.isEmpty()) {
    m_orderedQueue = sanitizeTrackList(savedShuffleSource, 2500);
  } else if (m_orderedQueue.isEmpty()) {
    m_orderedQueue = m_queue;
  }

  if (!m_track.isEmpty())
    return;

  QVariantMap saved =
      settings.value(QStringLiteral("playback/persistedTrack")).toMap();
  if (saved.value(QStringLiteral("id")).toString().isEmpty()) {
    saved = settings.value(QStringLiteral("playback/lastSong")).toMap();
  }
  const double savedPosition =
      settings.value(QStringLiteral("playback/persistedPosition"), 0.0)
          .toDouble();
  const double savedDuration =
      settings.value(QStringLiteral("playback/persistedDuration"), 0.0)
          .toDouble();
  const QString id = saved.value(QStringLiteral("id")).toString();
  const QString type = saved.value(QStringLiteral("type")).toString();

  if (id.isEmpty() || (type != "song" && type != "track" && type != "video")) {
    emit stateChanged();
    updateSystemMedia();
    return;
  }

  m_track = saved;
  m_audioEngineOutput.setActiveTrackId(m_track.value(QStringLiteral("id")).toString());
  m_track.remove(QStringLiteral("bitrate"));
  m_resumePosition = savedPosition;
  m_restoredDuration = savedDuration;
  m_resumePaused = true;
  m_loading = false;
  m_request = 0;
  emit stateChanged();
  updateSystemMedia();
}

void PlaybackController::setYouTubeHistoryEnabled(bool enabled) {
  if (m_youtubeHistoryEnabled == enabled)
    return;
  m_youtubeHistoryEnabled = enabled;
  QSettings().setValue(QStringLiteral("playback/youtubeHistoryEnabled"), enabled);
  emit stateChanged();
}

void PlaybackController::rememberStreamTracking(const QString &trackId,
                                                const QJsonObject &stream) {
  const QString playbackVideoId = stream.value("youtubeVideoId").toString();
  if (!trackId.isEmpty() && !playbackVideoId.isEmpty()) {
    // Only the current song and its preload matter; keep the current one on eviction.
    if (m_playbackVideoIds.size() >= 4) {
      const QString currentId = m_track.value("id").toString();
      const QString current = m_playbackVideoIds.value(currentId);
      m_playbackVideoIds.clear();
      if (!current.isEmpty())
        m_playbackVideoIds.insert(currentId, current);
    }
    m_playbackVideoIds.insert(trackId, playbackVideoId);
    // Qobuz audio is a different recording than the video SponsorBlock timed, so skip it.
    if (!stream.contains(QStringLiteral("playbackId")))
      requestNonMusicSegments(trackId, playbackVideoId, stream.value("durationSeconds").toDouble());
    else
      m_skipSegments.insert(trackId, {}); // Marked as asked, so no later path asks for it.
  }
  const QJsonObject tracking = stream.value("playbackTracking").toObject();
  if (historyDebug())
    qInfo() << "YouTube history: stream for" << trackId << "tracking"
            << !tracking.value("playbackUrl").toString().isEmpty();
  if (trackId.isEmpty() || tracking.value("playbackUrl").toString().isEmpty())
    return;
  // Only the current and preloaded songs matter; unplayed preloads get evicted.
  if (m_streamTracking.size() >= 4)
    m_streamTracking.clear();
  m_streamTracking.insert(trackId, tracking);
}

void PlaybackController::trackYouTubeHistory(double position) {
  const QString videoId = m_track.value(QStringLiteral("id")).toString();
  if (videoId != m_historyVideoId) {
    // Gapless and crossfade swap tracks without stop(), so close the old session here.
    reportYouTubeHistory(true);
    m_historyVideoId = videoId;
    const QJsonObject tracking = m_streamTracking.take(videoId);
    if (historyDebug())
      qInfo() << "YouTube history: playing" << videoId << "enabled"
              << m_youtubeHistoryEnabled << "tracking" << !tracking.isEmpty();
    if (!m_youtubeHistoryEnabled || tracking.isEmpty() || !m_auth->isSignedIn())
      return;
    static const QLatin1StringView alphabet(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_");
    m_historyCpn.clear();
    for (int index = 0; index < 16; ++index)
      m_historyCpn.append(alphabet.at(QRandomGenerator::global()->bounded(64)));
    m_historyTracking = tracking;
    m_historyPosition = position;
    m_historyReported = 0.0;
    m_historyRequests.insert(m_provider->invoke(
        QStringLiteral("history.start"),
        QJsonObject{{"session", m_auth->sessionObject()},
                    {"tracking", m_historyTracking},
                    {"cpn", m_historyCpn},
                    {"watchTime", position}}));
    return;
  }
  if (m_historyCpn.isEmpty())
    return;
  m_historyPosition = position;
  // Same 30 s cadence as the web client, so YouTube's watch time stays roughly honest.
  if (position >= m_historyReported + 30.0)
    reportYouTubeHistory(false);
}

void PlaybackController::reportYouTubeHistory(bool final) {
  if (final)
    m_historyVideoId.clear();
  if (m_historyCpn.isEmpty() || (!final && !m_youtubeHistoryEnabled))
    return;
  m_historyReported = m_historyPosition;
  if (historyDebug())
    qInfo() << "YouTube history: watch time" << m_historyPosition << "final" << final;
  m_historyRequests.insert(m_provider->invoke(
      QStringLiteral("history.update"),
      QJsonObject{{"session", m_auth->sessionObject()},
                  {"tracking", m_historyTracking},
                  {"cpn", m_historyCpn},
                  {"watchTime", m_historyPosition},
                  {"final", final}}));
  if (final) {
    m_historyCpn.clear();
    m_historyTracking = {};
  }
}

void PlaybackController::setPlaybackPersistenceEnabled(bool enabled) {
  if (m_persistenceEnabled == enabled)
    return;
  m_persistenceEnabled = enabled;
  QSettings().setValue(QStringLiteral("playback/persistenceEnabled"), enabled);
  if (enabled) {
    savePlaybackState();
  } else {
    m_persistTimer.stop();
    QSettings settings;
    settings.remove(QStringLiteral("playback/persistedTrack"));
    settings.remove(QStringLiteral("playback/persistedPosition"));
    settings.remove(QStringLiteral("playback/persistedDuration"));
    settings.remove(QStringLiteral("playback/persistedQueue"));
    settings.remove(QStringLiteral("playback/persistedHistory"));
    settings.remove(QStringLiteral("playback/persistedShuffleSource"));
    settings.remove(QStringLiteral("playback/lastSong"));
    settings.remove(QLatin1String(kPageKey));
  }
  emit stateChanged();
}
