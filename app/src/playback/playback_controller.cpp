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
#include "gapless_playback.h"
#include "auth/auth_manager.h"
#include "home/home_controller.h"
#include "local/local_track.h"
#include "providers/qobuz/qobuz_service.h"
#include "providers/youtube/youtube_provider.h"
#include "system_media_bridge.h"
#include <QDebug>
#include <QJsonObject>
#include <QScopedValueRollback>
#include <QSettings>
#include <algorithm>

PlaybackController::PlaybackController(YouTubeProvider *provider,
                                       AuthManager *auth, HomeController *home,
                                       SystemMediaBridge *systemMedia,
                                       Integrations *integrations,
                                       AnimatedArtworkService *animatedArtwork,
                                       QobuzService *qobuz, QObject *parent)
    : QObject(parent), m_provider(provider), m_qobuz(qobuz), m_auth(auth), m_home(home),
      m_systemMedia(systemMedia), m_integrations(integrations),
      m_bestMix(provider, auth), m_slop(provider, auth),
      m_animatedArtworkService(animatedArtwork) {
  connect(&m_adaptiveMix, &AdaptiveMixController::modeChanged, this, [this] {
    clearPreparedTrack();
    prepareNextTrack();
    emit stateChanged();
  });
  connect(&m_adaptiveMix, &AdaptiveMixController::changed, this, &PlaybackController::stateChanged);
  m_gaplessEnabled =
      QSettings().value("playback/gaplessEnabled", false).toBool();
  m_exponentialVolumeEnabled =
      QSettings().value("playback/exponentialVolumeEnabled", false).toBool();
  m_crossfadeEnabled =
      QSettings().value("playback/crossfadeEnabled", true).toBool();
  m_crossfadeDuration =
      qBound(1, QSettings().value("playback/crossfadeDuration", 6).toInt(), 12);
  m_autoplayEnabled =
      QSettings().value("playback/autoplayEnabled", true).toBool();
  m_streamQuality = QSettings()
                        .value("playback/streamQuality", QStringLiteral("high"))
                        .toString();
  if (m_streamQuality != "saver" && m_streamQuality != "normal" &&
      m_streamQuality != "high" && m_streamQuality != "max") {
    m_streamQuality = QStringLiteral("high");
  }
  m_persistenceEnabled =
      QSettings().value("playback/persistenceEnabled", true).toBool();
  m_youtubeHistoryEnabled =
      QSettings().value("playback/youtubeHistoryEnabled", true).toBool();
  loadNonMusicSkipMode();
  m_repeatMode =
      QSettings()
          .value(QStringLiteral("playback/repeatMode"), QStringLiteral("off"))
          .toString();
  if (m_repeatMode != "off" && m_repeatMode != "all" && m_repeatMode != "one") {
    m_repeatMode = QStringLiteral("off");
  }
  m_shuffleEnabled =
      QSettings()
          .value(QStringLiteral("playback/shuffleEnabled"), false)
          .toBool();
  m_persistTimer.setInterval(10000);
  connect(&m_persistTimer, &QTimer::timeout, this,
          &PlaybackController::savePlaybackState);
  if (m_persistenceEnabled) {
    QSettings settings;
    const QVariantList savedQueue =
        settings.value(QStringLiteral("playback/persistedQueue")).toList();
    const QVariantList savedHistory =
        settings.value(QStringLiteral("playback/persistedHistory")).toList();
    const QVariantList savedShuffleSource =
        settings.value(QStringLiteral("playback/persistedShuffleSource"))
            .toList();
    if (!savedQueue.isEmpty())
      m_queue = sanitizeTrackList(savedQueue, 2500);
    if (!savedHistory.isEmpty())
      m_history = sanitizeTrackList(savedHistory, 50);
    if (!savedShuffleSource.isEmpty())
      m_orderedQueue = sanitizeTrackList(savedShuffleSource, 2500);
    else
      m_orderedQueue = m_queue;
  }
  connect(this, &PlaybackController::queueChanged, this,
          &PlaybackController::prepareNextTrack, Qt::QueuedConnection);
  connect(this, &PlaybackController::queueChanged, this, [this] {
    if (m_bestMixSorted &&
        (m_queue.isEmpty() || !retainedBestMixOrder(m_queue, m_bestMixSortedQueue))) {
      m_bestMixSorted = false;
      m_bestMixOriginal.clear();
      m_bestMixSortedQueue.clear();
      emit bestMixStateChanged();
    }
    if (m_bestMix.busy() && m_queue.mid(0, 50) != m_bestMix.snapshot())
      m_bestMix.cancel();
  });
  connect(&m_bestMix, &BestMixController::orderReady, this,
          [this](const QVariantList &snapshot, const QList<int> &order) {
            if (m_queue.mid(0, 50) != snapshot) return;
            m_bestMixOriginal = m_queue;
            QVariantList sorted;
            for (int index : order) sorted.append(snapshot.at(index));
            for (int index = snapshot.size(); index < m_queue.size(); ++index)
              sorted.append(m_queue.at(index));
            m_queue = sorted;
            m_orderedQueue = sorted;
            m_bestMixSortedQueue = sorted;
            m_bestMixSorted = true;
            clearPreparedTrack();
            emit bestMixStateChanged();
            emit queueChanged();
            savePlaybackState();
          });
  connect(&m_audioEngineOutput, &AudioEngineOutput::crossfadeProgressChanged,
          this, [this](double progress) {
            if (!m_crossfadeActive)
              return;
            m_crossfadeProgress = std::clamp(progress, 0.0, 1.0);
            emit stateChanged();
          });
  connect(&m_audioEngineOutput, &AudioEngineOutput::crossfadeFinished, this,
          &PlaybackController::finishCrossfade);
  connect(&m_audioEngineOutput, &AudioEngineOutput::decodedAudio, &m_slop,
          &SlopDetector::feed);
  connect(&m_slop, &SlopDetector::flagged, this, [this](const QString &trackId) {
    // An outgoing track flagged mid-crossfade is already on its way out.
    if (m_slop.skips() && !m_crossfadeActive &&
        m_track.value(QStringLiteral("id")).toString() == trackId)
      next();
  });
  connect(&m_slop, &SlopDetector::flagsChanged, this,
          &PlaybackController::purgeFlaggedFromQueue);
  connect(this, &PlaybackController::queueChanged, this,
          &PlaybackController::purgeFlaggedFromQueue);
  // Whole-song scans run ahead of playback: the current track first, then what plays next.
  auto rescan = [this] {
    QVariantList tracks;
    if (!m_track.isEmpty() && !local::isLocalTrack(m_track))
      tracks.append(m_track);
    for (const QVariant &queued : m_queue.mid(0, 25)) {
      if (!local::isLocalTrack(queued.toMap()))
        tracks.append(queued);
    }
    m_slop.scan(tracks);
  };
  connect(this, &PlaybackController::queueChanged, this, rescan);
  connect(&m_slop, &SlopDetector::actionChanged, this, rescan);
  connect(this, &PlaybackController::stateChanged, this, [this, rescan] {
    const QString id = m_track.value(QStringLiteral("id")).toString();
    if (id == m_scannedTrackId)
      return;
    m_scannedTrackId = id;
    resolveTrackAlbum();
    rescan();
  });
  connect(provider, &YouTubeProvider::resultReady, this,
          &PlaybackController::receiveStream);
  connect(provider, &YouTubeProvider::requestFailed, this,
          [this](quint64 id, const QString &message) {
            if (m_historyRequests.remove(id)) {
              qWarning() << "YouTube history request failed:" << message;
              return;
            }
            if (failedNonMusicSegments(id))
              return;
            if (id == m_nextRequest && m_nextRequest) {
              m_nextRequest = 0;
              return; // Preload failures must never interrupt the current song.
            }
            if (id == m_albumRequest && m_albumRequest) {
              m_albumRequest = 0;
              return;
            }
            if (id == m_autoplayRequest && m_autoplayRequest) {
              m_autoplayRequest = 0;
              m_waitingForAutoplay = false;
              m_autoplayError = message;
              emit stateChanged();
            }
            if (id == m_request)
              fail(message);
            if (id == m_queueRequest) {
              m_queueRequest = 0;
              m_queueError = message;
              emit queueChanged();
            }
          });
  if (home) {
    applyMasterVolume();
    connect(home, &HomeController::volumeChanged, this, [this] {
      applyMasterVolume();
      updateSystemMedia();
    });
  }
  auto maybePrepareAndRestore = [this] {
    if (!m_prepared && m_auth->isSignedIn()) {
      m_prepared = true;
      m_provider->invoke("playback.prepare", QJsonObject{});
      restorePlayback();
    }
  };
  if (home) {
    connect(home, &HomeController::stateChanged, this,
            [this, home, maybePrepareAndRestore] {
              if (!m_prepared && m_auth->isSignedIn() && !home->loading() &&
                  !home->sections().isEmpty()) {
                maybePrepareAndRestore();
              }
            });
  }
  connect(auth, &AuthManager::sessionChanged, this,
          [this, maybePrepareAndRestore] {
            if (m_auth->isSignedIn()) {
              maybePrepareAndRestore();
            } else {
              clearQueue();
              m_history.clear();
              stop();
            }
          });
  if (m_auth->isSignedIn()) {
    maybePrepareAndRestore();
  }
  for (auto &streamProxy : m_proxies) {
    auto *proxy = &streamProxy;
    connect(proxy, &AudioStreamProxy::streamFailed, this,
            [this, proxy](int status, const QString &message) {
              if (proxy != m_proxy) {
                QMetaObject::invokeMethod(
                    this,
                    [this, proxy, status] {
                      // The Qt player and the mix worker share this proxy and
                      // can both fail on one bad URL; a pending retry covers both.
                      if (proxy == m_nextProxy && !m_nextRequest)
                        failPreparedTrack(status);
                    },
                    Qt::QueuedConnection);
                return;
              }
              // Queue recovery so the connection can finish its signal handler
              // safely.
              const auto generation = m_generation;
              QMetaObject::invokeMethod(
                  this,
                  [this, status, message, generation] {
                    if (generation != m_generation || m_track.isEmpty())
                      return;
                    // Recovery resolves album audio, so the theater must stop following.
                    if (!m_videoSourceTrackId.isEmpty()) {
                      m_videoSourceTrackId.clear();
                      emit videoSourceLost();
                    }
                    if (!m_retried && (status == 403 || status == 410 ||
                                       status == 429 || status >= 500)) {
                      m_retried = true;
                      reportQobuzEnded(m_proxy, m_player);
                      m_player->stop();
                      m_proxy->clear();
                      m_loading = true;
                      m_error.clear();
                      // A broken Qobuz stream retries on YouTube, as would a stale URL.
                      requestStream(true);
                      emit stateChanged();
                      updateSystemMedia();
                    } else
                      fail(message.isEmpty()
                               ? tr("YouTube audio request failed (HTTP %1).")
                                     .arg(status)
                               : message);
                  },
                  Qt::QueuedConnection);
            });
  }
  attachQobuz();

  if (m_systemMedia) {
    connect(m_systemMedia, &SystemMediaBridge::attachedChanged, this,
            &PlaybackController::updateSystemMedia);
    connect(m_systemMedia, &SystemMediaBridge::commandReceived, this,
            [this](const QString &kind, const QVariant &value) {
              if (kind == QStringLiteral("play")) {
                play();
              } else if (kind == QStringLiteral("pause")) {
                pause();
              } else if (kind == QStringLiteral("play-pause")) {
                toggle();
              } else if (kind == QStringLiteral("stop")) {
                stop();
              } else if (kind == QStringLiteral("next")) {
                next();
              } else if (kind == QStringLiteral("previous")) {
                previous();
              } else if (kind == QStringLiteral("seek")) {
                seek(value.toDouble());
              } else if (kind == QStringLiteral("seek-relative")) {
                seek(position() + value.toDouble());
              } else if (kind == QStringLiteral("set-shuffle")) {
                setShuffleEnabled(value.toBool());
              } else if (kind == QStringLiteral("set-repeat-mode")) {
                // Same loop, different name tag: the native bridge calls "all"
                // "queue".
                const QString mode = value.toString();
                setRepeatMode(mode == QStringLiteral("queue")
                                  ? QStringLiteral("all")
                                  : mode);
              } else if (kind == QStringLiteral("set-volume")) {
                if (m_home) {
                  m_home->setVolume(value.toDouble());
                }
              }
            });
  }
}

void PlaybackController::applyMasterVolume() {
  const double volume = m_home ? m_home->volume() : 1.0;
  // UI and remote volume values stay in slider space; only output gain is curved.
  m_audioEngineOutput.setMasterVolume(m_exponentialVolumeEnabled
                                        ? volume * volume * volume
                                        : volume);
}

void PlaybackController::setExponentialVolumeEnabled(bool enabled) {
  if (m_exponentialVolumeEnabled == enabled)
    return;
  m_exponentialVolumeEnabled = enabled;
  QSettings().setValue("playback/exponentialVolumeEnabled", enabled);
  applyMasterVolume();
  emit stateChanged();
}

void PlaybackController::stop() {
  reportYouTubeHistory(true);
  // Ignore timeline resets while both players are being cleared.
  clearPreparedTrack();
  m_loading = true;
  cancelAutoplay();
  ++m_generation;
  m_request = 0;
  m_failedPreparedPair.clear();
  reportQobuzEnded(m_proxy, m_player);
  m_proxy->clear();
  if (m_player) {
    m_player->stop();
    m_player->setSource(QUrl());
  }
  m_audioEngineOutput.setPlaying(false);
  m_audioEngineOutput.flush();

  m_track.clear();
  m_audioEngineOutput.setActiveTrackId(QString());
  updateAnimatedArtwork();
  m_error.clear();
  m_loading = false;
  m_retried = false;
  m_resumePosition = 0.0;
  m_restoredDuration = 0.0;
  m_resumePaused = false;
  m_lastPublishedPosition = 0.0;
  m_persistTimer.stop();
  emit stateChanged();
  updateSystemMedia();
}
