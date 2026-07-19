import { ref } from 'vue';

const MINIMUM_TRACK_SECONDS = 30;
const MAXIMUM_SCROBBLE_WAIT_SECONDS = 4 * 60;

function cleanText(value) {
  return String(value || '').trim().replace(/\s+/g, ' ');
}

export function lastfmTrackPayload(track, artistFallback = '', durationFallback = 0) {
  const title = cleanText(track?.title);
  const artistValue = track?.artist || track?.artists?.[0] || artistFallback;
  const artist = cleanText(typeof artistValue === 'string' ? artistValue : artistValue?.name);
  if (!title || !artist) return null;
  return {
    title,
    artist,
    album: cleanText(track?.album || track?.subtitle),
    albumArtist: cleanText(track?.albumArtist),
    duration: Math.max(0, Math.round(Number(durationFallback || track?.durationSeconds) || 0))
  };
}

export function shouldScrobble(duration, playedSeconds) {
  const length = Number(duration) || 0;
  if (length <= MINIMUM_TRACK_SECONDS) return false;
  return Number(playedSeconds) >= Math.min(length / 2, MAXIMUM_SCROBBLE_WAIT_SECONDS);
}

export function installLastfmScrobbling(ctx) {
  let current = null;
  let requestChain = Promise.resolve();

  ctx.lastfmState = ref({ status: 'loading', user: '', secureStorage: false });
  ctx.lastfmMessage = ref('');

  function setState(value) {
    ctx.lastfmState.value = {
      status: value?.status || 'disconnected',
      user: cleanText(value?.user),
      secureStorage: Boolean(value?.secureStorage),
      expiresAt: Number(value?.expiresAt) || 0
    };
  }

  function setError(error, fallback) {
    ctx.lastfmMessage.value = error?.message || fallback;
  }

  function enqueue(operation) {
    requestChain = requestChain
      .catch(() => {})
      .then(operation)
      .catch(async (error) => {
        setError(error, 'Last.fm is temporarily unavailable.');
        if (/session|connect/i.test(error?.message || '')) await ctx.loadLastfmStatus();
        return null;
      });
    return requestChain;
  }

  ctx.loadLastfmStatus = async function loadLastfmStatus() {
    if (!window.orchardLastfm) {
      setState({ status: 'unavailable' });
      return ctx.lastfmState.value;
    }
    try {
      setState(await window.orchardLastfm.status());
    } catch (error) {
      setState({ status: 'error' });
      setError(error, 'Could not load Last.fm status.');
    }
    return ctx.lastfmState.value;
  };

  ctx.connectLastfm = async function connectLastfm() {
    ctx.lastfmMessage.value = '';
    try {
      setState(await window.orchardLastfm.connect());
      ctx.lastfmMessage.value = 'Approve Orchard in your browser, then finish connecting here.';
    } catch (error) {
      setError(error, 'Could not start Last.fm connection.');
    }
  };

  ctx.completeLastfmConnection = async function completeLastfmConnection() {
    ctx.lastfmMessage.value = '';
    try {
      setState(await window.orchardLastfm.complete());
      ctx.lastfmMessage.value = `Connected as ${ctx.lastfmState.value.user}.`;
      ctx.startLastfmTrack();
    } catch (error) {
      setError(error, 'Last.fm authorization has not been approved yet.');
    }
  };

  ctx.disconnectLastfm = async function disconnectLastfm() {
    try {
      setState(await window.orchardLastfm.disconnect());
      current = null;
      ctx.lastfmMessage.value = 'Last.fm disconnected.';
    } catch (error) {
      setError(error, 'Could not disconnect Last.fm.');
    }
  };

  ctx.startLastfmTrack = function startLastfmTrack() {
    if (ctx.lastfmState.value.status !== 'connected' || !ctx.isPlaying.value || ctx.activeTrackIsLive.value) return;
    const track = lastfmTrackPayload(ctx.activeTrack.value, ctx.activeArtist.value, ctx.duration.value);
    if (!track) return;
    const key = [ctx.activeTrack.value?.id, track.artist, track.title].join(':');
    const position = Math.max(0, Number(ctx.currentTime.value) || 0);
    const isRepeat = current?.key === key && position < 2 && current.lastPosition > 5;
    if (current?.key === key && !isRepeat) {
      current.lastPosition = position;
      current.lastReportedAt = Date.now();
      return;
    }

    current = {
      key,
      track,
      timestamp: Math.max(1, Math.floor(Date.now() / 1000)),
      lastPosition: position,
      lastReportedAt: Date.now(),
      playedSeconds: 0,
      scrobbled: false,
      submitting: false
    };
    enqueue(() => window.orchardLastfm.updateNowPlaying(track));
  };

  ctx.reportLastfmProgress = function reportLastfmProgress() {
    if (ctx.lastfmState.value.status !== 'connected' || !ctx.isPlaying.value) return;
    if (!current) ctx.startLastfmTrack();
    if (!current) return;

    const position = Math.max(0, Number(ctx.currentTime.value) || 0);
    const progress = position - current.lastPosition;
    const elapsed = Math.max(0, (Date.now() - current.lastReportedAt) / 1000);
    if (progress > 0 && progress <= elapsed + 2) current.playedSeconds += Math.min(progress, elapsed);
    current.lastPosition = position;
    current.lastReportedAt = Date.now();
    current.track.duration = Math.max(current.track.duration, Math.round(Number(ctx.duration.value) || 0));
    if (current.scrobbled || current.submitting || !shouldScrobble(current.track.duration, current.playedSeconds)) return;

    current.submitting = true;
    const target = current;
    enqueue(async () => {
      try {
        const result = await window.orchardLastfm.scrobble(target.track, target.timestamp);
        target.scrobbled = true;
        ctx.lastfmMessage.value = result?.ignored
          ? (result.message || 'Last.fm ignored this track.')
          : `Scrobbled ${target.track.title}.`;
      } finally {
        target.submitting = false;
      }
    });
  };

  void ctx.loadLastfmStatus();
}
