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

void PlaybackController::wireStreamProxy(AudioStreamProxy *proxy) {
  connect(proxy, &AudioStreamProxy::streamFailed, this,
          [this, proxy](int status, const QString &message) {
            if (qEnvironmentVariableIsSet("ORCHARD_PLAYBACK_TIMING"))
              qInfo() << "Playback:" << (proxy == m_proxy ? "current" : "preload")
                      << "stream failed with HTTP" << status << message;
            if (proxy != m_proxy) {
              const auto generation = m_generation;
              const auto source = m_nextPlayer ? m_nextPlayer->source() : QUrl();
              QMetaObject::invokeMethod(this, [this, proxy, status, generation, source] {
                if (generation == m_generation && proxy == m_nextProxy &&
                    m_nextPlayer && source == m_nextPlayer->source() && !m_nextRequest)
                  failPreparedTrack(status);
              }, Qt::QueuedConnection);
              return;
            }
            const bool retryable = status == 0 || status == 403 || status == 410 ||
                                   status == 429 || status >= 500;
            scheduleStreamRecovery(retryable,
                message.isEmpty() ? tr("YouTube audio request failed (HTTP %1).").arg(status)
                                  : message);
          });
}

void PlaybackController::scheduleStreamRecovery(bool retryable, const QString &message) {
  if (!m_player || m_player->source().isEmpty() || m_track.isEmpty() || m_switchingTrack)
    return;
  const auto generation = m_generation;
  const auto source = m_player->source();
  auto *player = m_player;
  const auto timeline = m_playerTimelines.value(player);
  const double resumeAt = m_resumePosition > 0.0 ? m_resumePosition :
                         qMax(position(), timeline.position);
  const double streamDuration = duration();
  const bool paused = m_resumePaused || timeline.paused;
  // Both the proxy and decoder can report the same interruption. Defer teardown
  // until their handlers return, and invalidate sibling failures on recovery.
  QMetaObject::invokeMethod(this, [this, generation, source, player, retryable,
                                  message, resumeAt, streamDuration, paused] {
    if (generation != m_generation || player != m_player || source != player->source() ||
        m_track.isEmpty())
      return;
    if (!retryable || m_retried || m_proxy->stream().isEmpty()) {
      fail(message);
      return;
    }

    m_loading = true;
    clearPreparedTrack();
    // A completed mix may have promoted its incoming player during cancellation.
    if (player != m_player)
      return;
    m_retried = true;
    ++m_generation;
    m_request = 0;
    m_restoredDuration = streamDuration;
    m_error.clear();
    m_player->stop();
    m_player->setSource(QUrl());
    m_proxy->clear();
    m_audioEngineOutput.flush();
    // stop() can emit LoadedMedia; the resume seek belongs to the fresh source.
    m_resumePosition = resumeAt;
    m_resumePaused = paused;
    if (!m_videoSourceTrackId.isEmpty()) {
      m_videoSourceTrackId.clear();
      emit videoSourceLost();
    }
    requestStream(true);
    emit stateChanged();
    updateSystemMedia();
  }, Qt::QueuedConnection);
}
