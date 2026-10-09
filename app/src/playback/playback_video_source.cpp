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

// Music video mode plays the video's own soundtrack, as mobile does, so intros
// and edits never drift the picture away from the sound.

#include "local/local_track.h"
#include "playback_controller.h"

void PlaybackController::playVideoSource(const QJsonObject &stream) {
  const QString id = m_track.value(QStringLiteral("id")).toString();
  if (remoteActive() || m_crossfadeActive || id.isEmpty() || local::isLocalTrack(m_track) ||
      id == m_videoSourceTrackId)
    return;
  // A track restored from the last session has no player until it first plays.
  ensurePlaybackBackend();
  m_prepared = true;
  m_videoSourceTrackId = id;
  prepareSourceSwitch(id, position());
  startResolvedStream(stream);
}

void PlaybackController::playAlbumSource() {
  const QString id = m_track.value(QStringLiteral("id")).toString();
  const bool active = !id.isEmpty() && id == m_videoSourceTrackId;
  m_videoSourceTrackId.clear();
  if (!active || remoteActive())
    return;
  // The album cut can be shorter than the video; land just inside it.
  double at = position();
  const double albumLength = m_track.value(QStringLiteral("durationSeconds")).toDouble();
  if (albumLength > 1.0)
    at = qMin(at, albumLength - 1.0);
  prepareSourceSwitch(id, at);
  // The full restart path, as a quality change uses: it clears the old media first,
  // so the resume seek waits for the new stream to load.
  startTrack(QVariantMap(m_track), false, at, m_resumePaused);
}

void PlaybackController::prepareSourceSwitch(const QString &trackId, double at) {
  m_request = 0;
  m_resumePosition = qMax(0.0, at);
  m_resumePaused = !playing();
  if (m_player)
    m_player->pause();
  // Skip segments are timed against one stream; the new source asks again.
  m_skipSegments.remove(trackId);
  for (auto it = m_skipRequests.begin(); it != m_skipRequests.end();)
    it = it.value() == trackId ? m_skipRequests.erase(it) : std::next(it);
}
