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
