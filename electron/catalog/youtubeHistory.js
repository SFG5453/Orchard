// Obtains anonymous playback tracking URLs, then submits them with the signed-in
// browser session. This avoids authenticated /player requests, which YouTube
// rejects for some otherwise-valid Music sessions.
function cleanValue(value) {
  return String(value || '').trim();
}

function positiveSeconds(value) {
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : 0;
}

export function createYouTubeHistoryService({
  ensureSignedIn,
  getGuestInnertube,
  refreshBrowserAuth,
  sendBrowserHistoryStat
}) {
  const sessions = new Map();

  function sessionFor(sessionId, videoId) {
    const id = cleanValue(sessionId);
    const video = cleanValue(videoId);
    if (!id || !video) throw new Error('YouTube history requires a session and video ID');

    const existing = sessions.get(id);
    if (existing && existing.videoId !== video) sessions.delete(id);

    const session = sessions.get(id) || {
      videoId: video,
      info: null,
      tracking: null,
      request: Promise.resolve(),
      started: false
    };
    sessions.set(id, session);
    return session;
  }

  function enqueue(session, task) {
    session.request = session.request
      .catch(() => {})
      .then(task);
    return session.request;
  }

  async function loadTracking(session) {
    if (session.tracking || session.info) return;
    await refreshBrowserAuth();

    try {
      const guestYt = await getGuestInnertube?.();
      const info = guestYt && await guestYt.music.getInfo(session.videoId);
      const tracking = info?.page?.[0]?.playback_tracking;
      if (tracking?.videostats_playback_url && tracking?.videostats_watchtime_url && info.cpn) {
        session.tracking = { ...tracking, cpn: info.cpn };
        return;
      }
    } catch {
      // OAuth remains a compatibility fallback for guest player failures.
    }

    const signedInYt = await ensureSignedIn();
    session.info = await signedInYt.music.getInfo(session.videoId);
  }

  async function addToHistory(session) {
    await loadTracking(session);
    if (session.tracking) {
      await sendBrowserHistoryStat(session.tracking.videostats_playback_url, {
        cpn: session.tracking.cpn,
        fmt: 251,
        rtn: 0,
        rt: 0
      });
      return;
    }
    await session.info.addToWatchHistory();
  }

  async function updateHistory(session, watchTime) {
    await loadTracking(session);
    if (session.tracking) {
      const seconds = positiveSeconds(watchTime).toFixed(3);
      await sendBrowserHistoryStat(session.tracking.videostats_watchtime_url, {
        cpn: session.tracking.cpn,
        st: seconds,
        et: seconds,
        cmt: seconds,
        final: '1'
      });
      return;
    }
    await session.info.updateWatchTime(positiveSeconds(watchTime));
  }

  async function start({ sessionId, videoId }) {
    const session = sessionFor(sessionId, videoId);
    await enqueue(session, async () => {
      if (session.started) return;
      await addToHistory(session);
      session.started = true;
    });
    return { recorded: session.started };
  }

  async function update({ sessionId, videoId, watchTime, final = false }) {
    const session = sessionFor(sessionId, videoId);
    await enqueue(session, async () => {
      if (!session.started) {
        await addToHistory(session);
        session.started = true;
      }
      await updateHistory(session, watchTime);
      if (final) sessions.delete(cleanValue(sessionId));
    });
    return { recorded: true };
  }

  return {
    start,
    update
  };
}
