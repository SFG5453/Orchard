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
  connect(source, &QMediaPlayer::metaDataChanged, this,
          [this, source] { noteLocalMetadata(source); });
  connect(source, &QMediaPlayer::positionChanged, this, [this, source] {
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
            if (source != m_player)
              return;
            if (status == QMediaPlayer::LoadedMedia ||
                status == QMediaPlayer::BufferedMedia) {
              applyPendingResume();
            }
            if (status == QMediaPlayer::EndOfMedia) {
              if (m_crossfadeActive) {
                if (m_audioEngineOutput.adaptiveMixActive()) return;
                m_audioEngineOutput.completeCrossfade();
                return;
              }
              if (m_repeatMode == "one" && !m_track.isEmpty()) {
                startTrack(m_track);
              } else if (canGoNext()) {
                next();
              } else {
                updateSystemMedia();
              }
            }
          });
  connect(source, &QMediaPlayer::errorOccurred, this,
          [this, source](QMediaPlayer::Error, const QString &message) {
            if (source == m_player)
              fail(message);
            // An empty source means the error belongs to a preload already torn down.
            else if (source == m_nextPlayer && !source->source().isEmpty())
              failPreparedTrack(0);
          });
}

PlaybackController::~PlaybackController() {
  savePlaybackState();
  stop();
}
