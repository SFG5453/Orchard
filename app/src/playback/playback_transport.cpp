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
#include <QSettings>
#include <algorithm>
#include <cmath>

void PlaybackController::play() {
  if (forwardRemote(QStringLiteral("play")))
    return;
  if (m_loading || m_track.isEmpty())
    return;
  if ((!m_player || m_player->source().isEmpty()) && m_resumePaused) {
    const QVariantMap track = m_track;
    const double resumePosition = m_resumePosition;
    startTrack(track, false, resumePosition, false);
    return;
  }
  if (!m_error.isEmpty()) {
    startTrack(m_track);
    return;
  }
  if (!playing()) {
    m_proxy->setSuspended(false);
    if (m_crossfadeActive && m_nextProxy)
      m_nextProxy->setSuspended(false);
    m_player->play();
    if (m_crossfadeActive && m_nextPlayer)
      m_nextPlayer->play();
    updateSystemMedia();
  }
}

void PlaybackController::pause() {
  if (forwardRemote(QStringLiteral("pause")))
    return;
  m_waitingForAutoplay = false;
  if (playing()) {
    m_player->pause();
    if (m_crossfadeActive && m_nextPlayer)
      m_nextPlayer->pause();
    m_proxy->setSuspended(true);
    if (m_crossfadeActive && m_nextProxy)
      m_nextProxy->setSuspended(true);
    updateSystemMedia();
  }
}

void PlaybackController::toggle() {
  if (forwardRemote(QStringLiteral("toggle")))
    return;
  if (m_waitingForAutoplay) {
    pause();
    return;
  }
  if (m_loading || m_track.isEmpty())
    return;
  if ((!m_player || m_player->source().isEmpty()) && m_resumePaused) {
    const QVariantMap track = m_track;
    const double resumePosition = m_resumePosition;
    startTrack(track, false, resumePosition, false);
    return;
  }
  if (!m_error.isEmpty()) {
    startTrack(m_track);
    return;
  }
  if (playing())
    pause();
  else {
    m_proxy->setSuspended(false);
    if (m_crossfadeActive && m_nextProxy)
      m_nextProxy->setSuspended(false);
    m_player->play();
    if (m_crossfadeActive && m_nextPlayer)
      m_nextPlayer->play();
  }
  updateSystemMedia();
}

void PlaybackController::seek(double seconds) {
  if (std::isfinite(seconds) &&
      forwardRemote(QStringLiteral("seek"), {{QStringLiteral("position"), std::max(0.0, seconds)}}))
    return;
  if (!m_loading && std::isfinite(seconds)) {
    if (m_crossfadeActive) {
      cancelCrossfadeTransition();
      clearPreparedTrack();
    }
    if (m_player && m_player->isSeekable()) {
      const double target = qBound(0.0, seconds, duration());
      m_resumePosition = 0.0;
      m_audioEngineOutput.flush();
      m_player->setPosition(static_cast<qint64>(target * 1000));
      m_lastPublishedPosition = target;
      updateSystemMedia();
    } else {
      const double maxDuration = duration() > 0.0 ? duration() : 0.0;
      m_resumePosition = maxDuration > 0.0 ? qBound(0.0, seconds, maxDuration)
                                           : std::max(0.0, seconds);
      emit stateChanged();
    }
  }
}

void PlaybackController::seekBy(double seconds) {
  if (remoteActive()) {
    seek(shownPosition() + seconds);
    return;
  }
  if (m_loading || m_track.isEmpty() || !std::isfinite(seconds))
    return;
  // Chrono-navigation: rewinding 5 seconds won't undo your awkward
  // conversation, but it will replay that sick bass drop.
  seek(position() + seconds);
}

void PlaybackController::previous() {
  if (forwardRemote(QStringLiteral("previous")))
    return;
  if (m_loading || m_track.isEmpty())
    return;
  if (position() > 3.0 || m_history.isEmpty()) {
    seek(0.0);
  } else {
    const QVariantMap prev = m_history.takeLast().toMap();
    m_queue.prepend(m_track);
    m_orderedQueue.prepend(m_track);
    if (!m_cyclePlayed.isEmpty())
      m_cyclePlayed.removeLast();
    emit queueChanged();
    m_suppressHistory = true;
    startTrack(prev);
    m_suppressHistory = false;
  }
}

void PlaybackController::next() {
  if (forwardRemote(QStringLiteral("next")))
    return;
  if (m_loading)
    return;
  // Keep the full cycle separately: listening history deliberately caps at
  // fifty.
  if (m_queue.isEmpty() && m_repeatMode == "all" && !m_track.isEmpty()) {
    m_queue = m_cyclePlayed;
    m_queue.append(m_track);
    m_orderedQueue = m_queue;
    m_cyclePlayed.clear();
    if (m_shuffleEnabled)
      shuffleTracks(m_queue);
  } else if (!m_queue.isEmpty() && !m_track.isEmpty()) {
    m_cyclePlayed.append(m_track);
  }
  if (!m_queue.isEmpty()) {
    const QVariantMap nextTrack = m_queue.takeFirst().toMap();
    m_orderedQueue.removeOne(nextTrack);
    emit queueChanged();
    startTrack(nextTrack);
  } else if (m_autoplayEnabled && m_repeatMode == "off" && !local::isLocalTrack(m_track) && !offlineMode()) {
    ensureAutoplay();
    m_waitingForAutoplay = m_autoplayRequest != 0;
  } else if (duration() > 0.0) {
    seek(duration());
  }
}

void PlaybackController::setRepeatMode(const QString &mode) {
  if ((mode == "off" || mode == "all" || mode == "one") &&
      forwardRemote(QStringLiteral("set_repeat"), {{QStringLiteral("mode"), mode}}))
    return;
  if ((mode != "off" && mode != "all" && mode != "one") || mode == m_repeatMode)
    return;
  clearPreparedTrack();
  m_repeatMode = mode;
  QSettings().setValue(QStringLiteral("playback/repeatMode"), mode);
  if (mode != "off")
    cancelAutoplay();
  else
    ensureAutoplay();
  emit stateChanged();
  updateSystemMedia();
  savePlaybackState();
}

void PlaybackController::cycleRepeatMode() {
  const QString current = shownRepeatMode();
  setRepeatMode(current == "off"   ? "all"
                : current == "all" ? "one"
                                   : "off");
}
