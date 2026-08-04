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

const HISTORY_UPDATE_INTERVAL_SECONDS = 30;

function cleanValue(value) {
  return String(value || '').trim();
}

function positiveSeconds(value) {
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : 0;
}

function newSessionId(videoId) {
  return `${videoId}:${Date.now()}:${Math.random().toString(36).slice(2)}`;
}

export function installYouTubeHistoryActions(ctx) {
  let session = null;

  function enqueue(current, event, payload) {
    current.request = current.request
      .catch(() => {})
      .then(() => ctx.emitWithReply(event, payload))
      .catch(() => null);
  }

  ctx.startYouTubeHistory = function startYouTubeHistory(videoId) {
    const id = cleanValue(videoId);
    if (!ctx.youtubeHistoryEnabled.value || !id || !ctx.socket.value?.connected) return;
    if (session?.videoId === id) return;

    if (session) ctx.finishYouTubeHistory();
    session = {
      sessionId: newSessionId(id),
      videoId: id,
      lastReportedSeconds: 0,
      request: Promise.resolve()
    };
    enqueue(session, 'music:history:start', {
      sessionId: session.sessionId,
      videoId: session.videoId
    });
  };

  ctx.reportYouTubeHistoryProgress = function reportYouTubeHistoryProgress({ force = false, final = false } = {}) {
    const current = session;
    if (!current || (!ctx.youtubeHistoryEnabled.value && !final) || !ctx.socket.value?.connected) return;

    const watchTime = positiveSeconds(ctx.currentTime.value);
    if (!force && !final && watchTime < current.lastReportedSeconds + HISTORY_UPDATE_INTERVAL_SECONDS) return;
    if (watchTime < current.lastReportedSeconds && !final) return;
    current.lastReportedSeconds = watchTime;

    enqueue(current, 'music:history:update', {
      sessionId: current.sessionId,
      videoId: current.videoId,
      watchTime,
      final
    });

    if (final) session = null;
  };

  ctx.finishYouTubeHistory = function finishYouTubeHistory() {
    ctx.reportYouTubeHistoryProgress({ force: true, final: true });
  };
}
