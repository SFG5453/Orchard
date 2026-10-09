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

// Track starts and quality switches, split out of playback_controller.cpp to
// keep both files readable.

#include "playback_controller.h"
#include "playback_helpers.h"
#include "gapless_playback.h"
#include "auth/auth_manager.h"
#include "local/local_track.h"
#include "providers/qobuz/qobuz_service.h"
#include <QDebug>
#include <QScopedValueRollback>
#include <QSettings>

void PlaybackController::playSong(const QVariantMap &track) {
  if (forwardRemote(QStringLiteral("play_track"), {{QStringLiteral("track"), track}}))
    return;
  startTrack(track, true);
}

void PlaybackController::startTrack(const QVariantMap &track, bool replaceQueue,
                                    double initialPosition, bool startPaused) {
  const QString type = track.value("type").toString();
  // Video rows still have playable audio. The resolver checks their duration;
  // the type label is not a bouncer with a very small guest list.
  if ((type != "song" && type != "track" && type != "video") ||
      track.value("id").toString().isEmpty() ||
      track.value("unplayable").toBool() ||
      (!m_auth->isSignedIn() && !local::isLocalTrack(track) && downloadedFileFor(track).isEmpty()))
    return;
  ensurePlaybackBackend();
  if (m_crossfadeActive)
    cancelCrossfadeTransition();
  m_prepared = true;
  const QVariantMap selected = track;
  if (replaceQueue) {
    m_autoplaySuppressed.clear();
    cancelQueueLoading();
    // New song, new guest list. Transport controls keep their existing line.
    m_queue.clear();
    m_orderedQueue.clear();
    m_cyclePlayed.clear();
    m_history.clear();
    emit queueChanged();
  }

  if (!replaceQueue && !m_suppressHistory && !m_track.isEmpty() &&
      m_track.value("id") != selected.value("id")) {
    m_history.append(m_track);
    if (m_history.size() > 50) {
      m_history.removeFirst();
    }
  }

  // Paused preloads may still report BufferingMedia. Preserve their decoder
  // and network request, including loads still in progress, instead of starting
  // the same song over from scratch at the track boundary.
  const bool prepared =
      m_gaplessEnabled && !replaceQueue && initialPosition == 0.0 &&
      !startPaused && selected.value("id") == m_preparedTrack.value("id") &&
      !m_nextPlayer->source().isEmpty() &&
      canReuseGaplessMedia(m_nextPlayer->mediaStatus(), m_nextPlayer->error());
  if (m_gaplessEnabled && qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
    qInfo() << "Playback: gapless handoff" << prepared
            << "preload status" << m_nextPlayer->mediaStatus();
  if (prepared) {
    QScopedValueRollback<bool> switchingTrack(m_switchingTrack, true);
    cancelAutoplay();
    ++m_generation;
    m_request = 0;
    m_nextRequest = 0;
    reportQobuzEnded(m_proxy, m_player);
    m_player->stop();
    // Keep each decoder attached to its output. Musical chairs are for parties,
    // not audio sinks that already have the next song buffered.
    std::swap(m_player, m_nextPlayer);
    std::swap(m_proxy, m_nextProxy);
    m_audioEngineOutput.setCurrentPlayer(m_player);
    m_track = selected;
    m_videoSourceTrackId.clear();
    m_audioEngineOutput.setActiveTrackId(m_track.value(QStringLiteral("id")).toString());
    m_track.insert(QStringLiteral("bitrate"), m_preparedBitrate);
    applyStreamQuality();
    reportQobuzStarted(m_proxy, 0.0);
    m_loading = false;
    m_error.clear();
    m_retried = false;
    m_resumePosition = 0.0;
    m_resumePaused = false;
    m_lastPublishedPosition = 0.0;
    m_player->play();
    clearPreparedTrack();
    updateAnimatedArtwork();
    emit stateChanged();
    m_switchingTrack = false;
    updateSystemMedia();
    ensureAutoplay();
    savePlaybackState();
    return;
  }

  {
    // Reset the player without making Discord leave and rejoin the party.
    // stop() emits intermediate player updates, including an empty track.
    QScopedValueRollback<bool> switchingTrack(m_switchingTrack, true);
    stop();
  }
  m_resumePosition = initialPosition;
  m_resumePaused = startPaused;
  m_startupTimer.start();
  m_reportedAudio = false;
  m_track = selected;
  m_videoSourceTrackId.clear();
  m_audioEngineOutput.setActiveTrackId(m_track.value(QStringLiteral("id")).toString());
  updateAnimatedArtwork();
  // Wipe previous bitrate so we don't display yesterday's audio fidelity today.
  m_track.remove(QStringLiteral("bitrate"));
  m_loading = true;
  m_lastPublishedPosition = 0.0;
  if (local::isLocalTrack(m_track)) {
    startLocalFile();
  } else if (const QString file = downloadedFileFor(m_track); !file.isEmpty()) {
    startDownloadedFile(file);
  } else if (offlineMode()) {
    fail(tr("“%1” is not downloaded, so it cannot play offline.")
             .arg(m_track.value(QStringLiteral("title")).toString()));
  } else {
    m_auth->refreshSession();
    requestStream();
  }
  emit stateChanged();
  updateSystemMedia();
  ensureAutoplay();
  savePlaybackState();
}

void PlaybackController::setStreamQuality(const QString &quality) {
  if ((quality != "saver" && quality != "normal" && quality != "high" &&
       quality != "max") ||
      quality == m_streamQuality)
    return;
  // MAX is Qobuz; without an account here or on a Connect peer there is nothing to turn up to.
  if (quality == "max" && !qobuzReachable())
    return;
  clearPreparedTrack();
  m_streamQuality = quality;
  QSettings().setValue("playback/streamQuality", quality);
  if (m_qobuz)
    m_qobuz->setEnabled(quality == "max");
  emit streamQualityChanged();
  if (m_track.isEmpty() || !m_auth->isSignedIn())
    return;
  if ((!m_player || m_player->source().isEmpty()) && m_resumePaused)
    return;
  const QVariantMap track = m_track;
  const double resumePosition =
      m_resumePosition > 0 ? m_resumePosition : position();
  const bool resumePaused =
      m_resumePaused || m_player->playbackState() == QMediaPlayer::PausedState;
  // Switching codecs should not send the listener back to the opening credits.
  startTrack(track, false, resumePosition, resumePaused);
}
