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
#include <QDebug>
#include <cmath>

void PlaybackController::ensurePlaybackBackend() {
  if (m_player)
    return;

  for (int index = 0; index < 2; ++index) {
    m_players[index] = std::make_unique<QMediaPlayer>();
    m_audioEngineOutput.attachPlayer(m_players[index].get());
    wirePlayer(m_players[index].get());
  }

  m_player = m_players[0].get();
  m_nextPlayer = m_players[1].get();
  m_audioEngineOutput.setCurrentPlayer(m_player);
}

void PlaybackController::wirePlayer(QMediaPlayer *source) {
  connect(source, &QMediaPlayer::sourceChanged, this,
          [this, source] { m_playerTimelines[source] = {}; });
  connect(source, &QMediaPlayer::metaDataChanged, this,
          [this, source] { noteLocalMetadata(source); });
  connect(source, &QMediaPlayer::positionChanged, this, [this, source](qint64 position) {
    // A decoder can reset its clock before reporting an error.
    if (position > 0)
      m_playerTimelines[source].position = position / 1000.0;
    if (source == m_player)
      emit stateChanged();
  });
  connect(source, &QMediaPlayer::positionChanged, this,
          [this, source](qint64 position) {
            if (source != m_player)
              return;
            prepareNextTrack();
            maybeStartCrossfade();
            if (position > 0 && !m_reportedAudio &&
                m_startupTimer.isValid()) {
              m_reportedAudio = true;
              if (qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
                qInfo() << "Playback: timeline advancing after"
                        << m_startupTimer.elapsed() << "ms";
            }
            const double posSec = position / 1000.0;
            if (position > 0 && source->playbackState() == QMediaPlayer::PlayingState)
              trackYouTubeHistory(posSec);
            if (position > 0 && source->playbackState() == QMediaPlayer::PlayingState)
              autoSkipNonMusic(posSec);
            if (std::abs(posSec - m_lastPublishedPosition) >= 1.0) {
              m_lastPublishedPosition = posSec;
              updateSystemMedia();
            }
          });
  connect(source, &QMediaPlayer::durationChanged, this, [this, source] {
    if (source != m_player)
      return;
    emit stateChanged();
    updateSystemMedia();
  });
  connect(source, &QMediaPlayer::playbackStateChanged, this,
          [this, source](QMediaPlayer::PlaybackState state) {
            if (state != QMediaPlayer::StoppedState)
              m_playerTimelines[source].paused = state == QMediaPlayer::PausedState;
            if (source != m_player)
              return;
            if (m_crossfadeActive && state == QMediaPlayer::StoppedState &&
                m_nextPlayer &&
                m_nextPlayer->playbackState() == QMediaPlayer::PlayingState) {
              emit stateChanged();
              updateSystemMedia();
              return;
            }
            if (state == QMediaPlayer::PlayingState) {
              m_loading = false;
              if (m_persistenceEnabled)
                m_persistTimer.start();
            } else {
              m_persistTimer.stop();
              if (state == QMediaPlayer::PausedState)
                savePlaybackState();
            }
            m_audioEngineOutput.setPlaying(state == QMediaPlayer::PlayingState);
            emit stateChanged();
            updateSystemMedia();
          });
  connect(source, &QMediaPlayer::seekableChanged, this, [this, source](bool) {
    if (source == m_player)
      applyPendingResume();
  });
  connect(source, &QMediaPlayer::mediaStatusChanged, this,
          [this, source](QMediaPlayer::MediaStatus status) {
            if (status == QMediaPlayer::EndOfMedia)
              scheduleTrackEnd(source);
            if (source != m_player)
              return;
            if (status == QMediaPlayer::LoadedMedia ||
                status == QMediaPlayer::BufferedMedia) {
              applyPendingResume();
            }
          });
  connect(source, &QMediaPlayer::errorOccurred, this,
          [this, source](QMediaPlayer::Error error, const QString &message) {
            if (source->source().isEmpty())
              return;
            if (qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
              qInfo() << "Playback:" << (source == m_player ? "current" : "preload")
                      << "decoder error" << error << message;
            if (source == m_player) {
              const bool retryable = error == QMediaPlayer::ResourceError ||
                                     error == QMediaPlayer::NetworkError;
              scheduleStreamRecovery(retryable, message);
            }
            // An empty source means the error belongs to a preload already torn down.
            else if (source == m_nextPlayer && !source->source().isEmpty())
              failPreparedTrack(0);
          });
}

void PlaybackController::scheduleTrackEnd(QMediaPlayer *source) {
  if (source != m_player && !(m_crossfadeActive && source == m_nextPlayer))
    return;
  const auto generation = m_generation;
  const auto url = source->source();
  // Let the decoder finish its notification before switching or clearing it.
  QMetaObject::invokeMethod(this, [this, source, generation, url] {
    if (generation != m_generation || source->source() != url ||
        source->mediaStatus() != QMediaPlayer::EndOfMedia)
      return;
    if (m_crossfadeActive && (source == m_player || source == m_nextPlayer)) {
      if (!m_audioEngineOutput.adaptiveMixActive())
        m_audioEngineOutput.completeCrossfade();
      return;
    }
    if (source != m_player)
      return;
    if (m_repeatMode == "one" && !m_track.isEmpty())
      startTrack(m_track);
    else if (canGoNext())
      next();
    else
      updateSystemMedia();
  }, Qt::QueuedConnection);
}

PlaybackController::~PlaybackController() {
  savePlaybackState();
  stop();
}
