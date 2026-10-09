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

// Songs that live on this computer. No proxy, no provider, no sign-in: the
// player opens the file and the rest of the pipeline cannot tell the difference.

#include "playback_controller.h"
#include "local/local_track.h"
#include <QFileInfo>
#include <QMediaMetaData>
#include <QUrl>

namespace {

// Bits per second from what the library probed, else from size over duration.
int estimateBitrate(const QVariantMap &track, double durationSeconds) {
  const int probed = track.value(QStringLiteral("localBitrate")).toInt();
  if (probed > 0)
    return probed;
  const qint64 size = QFileInfo(track.value(QStringLiteral("localPath")).toString()).size();
  const double seconds = durationSeconds > 0 ? durationSeconds : track.value(QStringLiteral("durationSeconds")).toDouble();
  return size > 0 && seconds > 0 ? static_cast<int>((size * 8) / seconds) : 0;
}

} // namespace

// The player's decoder knows the bitrate better than any tag does, so its
// metadata wins whenever it shows up. Detective work, minus the trench coat.
void PlaybackController::noteLocalMetadata(QMediaPlayer *source) {
  if (source != m_player || !local::isLocalTrack(m_track))
    return;
  int bitrate = source->metaData().value(QMediaMetaData::AudioBitRate).toInt();
  if (bitrate <= 0)
    bitrate = estimateBitrate(m_track, source->duration() / 1000.0);
  if (bitrate <= 0 || bitrate == m_track.value(QStringLiteral("bitrate")).toInt())
    return;
  m_track.insert(QStringLiteral("bitrate"), bitrate);
  emit stateChanged();
}

void PlaybackController::startLocalFile() {
  openFile(m_track.value(QStringLiteral("localPath")).toString(), estimateBitrate(m_track, 0));
}

void PlaybackController::openFile(const QString &path, int bitrate) {
  if (!QFileInfo::exists(path)) {
    fail(tr("“%1” could not be found. It may have been moved or deleted.")
             .arg(m_track.value(QStringLiteral("title")).toString()));
    return;
  }
  m_request = 0;
  // A stale stream from the previous song must not paint a Qobuz badge on this one.
  m_proxy->clear();
  applyStreamQuality();
  m_player->setSource(QUrl::fromLocalFile(path));
  if (m_resumePaused) {
    m_player->pause();
    m_loading = false;
    m_resumePaused = false;
  } else {
    m_player->play();
  }
  applyPendingResume();
  if (bitrate > 0)
    m_track.insert(QStringLiteral("bitrate"), bitrate);
  m_restoringPlayback = false;
  savePlaybackState();
  if (m_persistenceEnabled && playing())
    m_persistTimer.start();
}

void PlaybackController::openPreparedLocal() {
  openPreparedFile(m_preparedTrack.value(QStringLiteral("localPath")).toString(),
                   estimateBitrate(m_preparedTrack, 0));
}

void PlaybackController::openPreparedFile(const QString &path, int bitrate) {
  // A missing file is reported by startTrack at the boundary, where the user can see it.
  if (!QFileInfo::exists(path))
    return;
  m_preparedBitrate = bitrate;
  m_nextPlayer->setSource(QUrl::fromLocalFile(path));
  m_nextPlayer->pause();
  maybeStartCrossfade();
}
