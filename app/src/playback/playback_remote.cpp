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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Remote mode: while this desktop controls another device, the UI shows that
// device's player and every button press becomes a Connect command.

#include "playback_controller.h"

#include "auth/auth_manager.h"

#include <algorithm>

void PlaybackController::setRemote(PlaybackRemote *remote) {
  m_remote = remote;
  remoteChanged();
}

void PlaybackController::setRemoteProvider(RemoteProvider *provider) {
  m_remoteProvider = provider;
  // Rebuild the readers so remote streams can reach the provider host.
  installRangeReaders();
}

void PlaybackController::remoteChanged() {
  emit stateChanged();
  emit queueChanged();
}

bool PlaybackController::forwardRemote(const QString &action, const QVariantMap &args) {
  if (!remoteActive())
    return false;
  m_remote->command(action, args);
  return true;
}

QVariantMap PlaybackController::shownTrack() const { return remoteActive() ? m_remote->track() : m_track; }

QVariantList PlaybackController::shownQueue() const { return remoteActive() ? m_remote->queue() : m_queue; }

bool PlaybackController::shownPlaying() const { return remoteActive() ? m_remote->playing() : playing(); }

bool PlaybackController::shownLoading() const { return remoteActive() ? m_remote->loading() : m_loading; }

double PlaybackController::shownPosition() const { return remoteActive() ? m_remote->position() : position(); }

double PlaybackController::shownAudiblePosition() const {
  return remoteActive() ? m_remote->position() : audiblePosition();
}

double PlaybackController::shownDuration() const { return remoteActive() ? m_remote->duration() : duration(); }

double PlaybackController::shownDisplayPosition() const {
  return remoteActive() ? m_remote->position() : displayPosition();
}

double PlaybackController::shownDisplayDuration() const {
  return remoteActive() ? m_remote->duration() : displayDuration();
}

bool PlaybackController::shownShuffle() const { return remoteActive() ? m_remote->shuffle() : m_shuffleEnabled; }

QString PlaybackController::shownRepeatMode() const { return remoteActive() ? m_remote->repeat() : m_repeatMode; }

bool PlaybackController::shownCanGoNext() const {
  if (!remoteActive())
    return canGoNext();
  return !m_remote->queue().isEmpty() || m_remote->repeat() == QStringLiteral("all");
}

bool PlaybackController::shownCanGoPrevious() const {
  return remoteActive() ? !m_remote->track().isEmpty() : canGoPrevious();
}

void PlaybackController::playFrom(const QVariantList &tracks, int index, double position, bool play) {
  if (tracks.isEmpty() || !m_auth->isSignedIn())
    return;
  index = std::clamp(index, 0, static_cast<int>(tracks.size()) - 1);
  cancelQueueLoading();
  m_autoplaySuppressed.clear();
  m_playlistId.clear();
  m_queueContinuation.clear();
  // Earlier songs stay reachable through Previous, as with a collection.
  m_cyclePlayed = tracks.mid(0, index);
  m_history = m_cyclePlayed.mid(std::max<qsizetype>(0, m_cyclePlayed.size() - 50));
  m_queue = tracks.mid(index + 1);
  m_orderedQueue = m_queue;
  emit queueChanged();
  m_suppressHistory = true;
  startTrack(tracks.at(index).toMap(), false, std::max(0.0, position), !play);
  m_suppressHistory = false;
}
