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

// Downloaded songs play from disk; offline mode keeps the queue to what can play.

#include "local/local_track.h"
#include "offline/connectivity_monitor.h"
#include "offline/download_manager.h"
#include "playback_controller.h"

void PlaybackController::setOfflineSources(DownloadManager *downloads, ConnectivityMonitor *connectivity) {
  m_downloads = downloads;
  m_connectivity = connectivity;
  if (!connectivity)
    return;
  connect(connectivity, &ConnectivityMonitor::lost, this, &PlaybackController::applyOffline);
  connect(connectivity, &ConnectivityMonitor::restored, this, [this] {
    ensureAutoplay();
    emit stateChanged();
  });
}

bool PlaybackController::offlineMode() const { return m_connectivity && m_connectivity->offline(); }

QString PlaybackController::downloadedFileFor(const QVariantMap &track) const {
  if (!m_downloads || local::isLocalTrack(track))
    return {};
  const QString path = m_downloads->audioPathFor(track.value(QStringLiteral("id")).toString());
  // Online MAX streams the lossless Qobuz match; the saved copy is YouTube audio.
  if (path.isEmpty() || (!offlineMode() && qobuzEligible(track)))
    return {};
  return path;
}

void PlaybackController::startDownloadedFile(const QString &path) {
  openFile(path, m_downloads->bitrateFor(m_track.value(QStringLiteral("id")).toString()));
}

// Queue entries that need the network are dropped when the connection is lost; the algorithm stays home.
void PlaybackController::applyOffline() {
  cancelAutoplay();
  const auto playable = [this](const QVariant &entry) {
    const QVariantMap track = entry.toMap();
    return local::isLocalTrack(track) || !downloadedFileFor(track).isEmpty();
  };
  const qsizetype before = m_queue.size();
  m_queue.removeIf([&](const QVariant &entry) { return !playable(entry); });
  m_orderedQueue.removeIf([&](const QVariant &entry) { return !playable(entry); });
  m_cyclePlayed.removeIf([&](const QVariant &entry) { return !playable(entry); });
  if (!m_preparedTrack.isEmpty() && !playable(m_preparedTrack))
    clearPreparedTrack();
  if (m_queue.size() != before)
    emit queueChanged();
  emit stateChanged();
  updateSystemMedia();
}
