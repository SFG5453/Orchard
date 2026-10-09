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
#include "appearance/animated_artwork_service.h"
#include "home/home_controller.h"
#include "integrations/integrations.h"
#include "offline/download_manager.h"
#include "local/local_track.h"
#include "system_media_bridge.h"

void PlaybackController::updateSystemMedia() {
  if (!m_systemMedia) {
    updateIntegrations();
    return;
  }

  QVariantMap state;
  if (!m_track.isEmpty()) {
    state.insert(QStringLiteral("track"), m_track);
  }
  state.insert(QStringLiteral("isPlaying"), playing());
  state.insert(QStringLiteral("canGoNext"), canGoNext());
  state.insert(QStringLiteral("canGoPrevious"), canGoPrevious());
  state.insert(QStringLiteral("canSeek"),
               duration() > 0.0 && !m_loading &&
                   ((m_player && m_player->isSeekable()) || m_resumePaused));
  state.insert(QStringLiteral("currentTime"), position());
  state.insert(QStringLiteral("durationSeconds"), duration());
  state.insert(QStringLiteral("volume"),
               m_home ? m_home->volume() : 1.0);
  state.insert(QStringLiteral("repeatMode"), m_repeatMode);
  state.insert(QStringLiteral("shuffleEnabled"), m_shuffleEnabled);

  m_systemMedia->publish(state);
  updateIntegrations();
}

void PlaybackController::updateIntegrations() {
  if (!m_integrations || m_switchingTrack)
    return;
  QVariantMap track = m_track;
  // Only hand over motion artwork that belongs to this track.
  if (!m_animatedArtworkUrl.isEmpty() &&
      m_animatedArtworkTrackId == m_track.value(QStringLiteral("id")).toString())
    track.insert(QStringLiteral("animatedArtworkUrl"), m_animatedArtworkUrl);
  m_integrations->updatePlayback(track, playing(), position(), duration(),
                                 m_crossfadeActive,
                                 m_crossfadeTrack.value(QStringLiteral("title")).toString());
}

// Look ma, no hands! And by hands, we mean static album art from the Bronze
// Age.
void PlaybackController::updateAnimatedArtwork() {
  if (!m_animatedArtworkService) {
    m_lastArtworkTrackId.clear();
    if (!m_animatedArtworkUrl.isEmpty()) {
      m_animatedArtworkUrl.clear();
      emit animatedArtworkUrlChanged();
    }
    return;
  }

  const QString id = m_track.value(QStringLiteral("id")).toString();
  const QString title = m_track.value(QStringLiteral("title")).toString();
  const QString artist = m_track.value(QStringLiteral("artist")).toString();
  const QString album = m_track.value(QStringLiteral("album")).toString();

  // Local songs never ask the mirrors. Their loop is whatever the user chose, if anything.
  // Downloads reuse the loop saved with them, and stay quiet offline.
  const QString savedLoop = m_downloads ? m_downloads->animatedUrlFor(id) : QString();
  if (local::isLocalTrack(m_track) || !savedLoop.isEmpty() || offlineMode()) {
    ++m_artworkRequestId; // Orphans any lookup still in flight for the previous song.
    m_lastArtworkTrackId = id;
    m_animatedArtworkTrackId = id;
    const QString own = local::isLocalTrack(m_track) ? m_track.value(QStringLiteral("localAnimatedCover")).toString()
                                                      : savedLoop;
    if (m_animatedArtworkUrl != own) {
      m_animatedArtworkUrl = own;
      emit animatedArtworkUrlChanged();
    }
    return;
  }

  if (title.trimmed().isEmpty() || id.isEmpty()) {
    m_lastArtworkTrackId.clear();
    if (!m_animatedArtworkUrl.isEmpty()) {
      m_animatedArtworkUrl.clear();
      emit animatedArtworkUrlChanged();
    }
    return;
  }

  // Fetch once per song: if we're already rocking this track's motion artwork,
  // don't ask again.
  if (m_lastArtworkTrackId == id && !m_animatedArtworkUrl.isEmpty()) {
    return;
  }
  m_lastArtworkTrackId = id;

  // Drop the previous track's loop so it cannot resurface while this lookup is pending.
  // Last song's video, this song's audio: the music-video equivalent of a bad lip sync.
  if (m_animatedArtworkTrackId != id && !m_animatedArtworkUrl.isEmpty()) {
    m_animatedArtworkUrl.clear();
    emit animatedArtworkUrlChanged();
  }

  const quint64 reqId = ++m_artworkRequestId;
  m_animatedArtworkService->resolveTrackArtwork(
      title, artist, album, reqId,
      [this, id](quint64 requestId, const QString &videoUrl) {
        if (requestId != m_artworkRequestId)
          return;
        m_animatedArtworkTrackId = id;
        if (m_animatedArtworkUrl != videoUrl) {
          m_animatedArtworkUrl = videoUrl;
          emit animatedArtworkUrlChanged();
        }
        // Runs even when the URL is unchanged: the same album loop is new to
        // Discord for this track.
        updateIntegrations();
      });
}
