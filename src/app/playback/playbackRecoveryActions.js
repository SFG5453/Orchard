import { audioRecoveryPlan, videoRecoveryPlan } from './audioRecovery.js';

function isSourceFormatError(error) {
  const message = String(error?.message || error || '');
  return error?.code === (globalThis.MediaError?.MEDIA_ERR_SRC_NOT_SUPPORTED || 4) ||
    /format error|no supported source|not playable|src_not_supported/i.test(message);
}

export function installPlaybackRecoveryActions(ctx) {
  let activeRecovery = null;

  ctx.retryAudioWithAlternateFormat = function retryAudioWithAlternateFormat(track = ctx.activeTrack.value, options = {}) {
    if (!track || track.mediaKind === 'video' || !track.itag) return false;
    return ctx.retryAudioStream(track, { ...options, avoidCurrentFormat: true });
  };

  ctx.retryAudioStream = function retryAudioStream(track = ctx.activeTrack.value, options = {}) {
    if (!track || track.mediaKind === 'video' || (!options.refreshStream && !track.itag)) return false;
    if (activeRecovery?.trackId === track.id) return activeRecovery.promise;

    const recovery = audioRecoveryPlan(track, options);
    if (!recovery) return false;
    const promise = ctx.playTrack(recovery.track, {
      mediaKind: 'audio',
      recoveryAttempt: true,
      skipHistory: true,
      preserveQueue: true,
      refreshStream: Boolean(options.refreshStream),
      avoidItags: recovery.avoidItags,
      avoidMimeTypes: recovery.avoidMimeTypes,
      resumeAt: options.resumeAt
    });
    const pending = { trackId: track.id, promise };
    activeRecovery = pending;
    const clearPending = () => {
      if (activeRecovery === pending) activeRecovery = null;
    };
    void Promise.resolve(promise).then(clearPending, clearPending);
    return promise;
  };

  ctx.retryVideoStream = function retryVideoStream(track = ctx.activeTrack.value, options = {}) {
    if (!track || track.mediaKind !== 'video' || (!options.refreshStream && !track.itag)) return false;
    if (activeRecovery?.trackId === track.id) return activeRecovery.promise;

    const recovery = videoRecoveryPlan(track, options);
    if (!recovery) return false;
    const promise = ctx.playTrack(recovery.track, {
      mediaKind: 'video',
      recoveryAttempt: true,
      skipHistory: true,
      preserveQueue: true,
      refreshStream: Boolean(options.refreshStream),
      avoidItags: recovery.avoidItags,
      resumeAt: options.resumeAt
    });
    const pending = { trackId: track.id, promise };
    activeRecovery = pending;
    const clearPending = () => {
      if (activeRecovery === pending) activeRecovery = null;
    };
    void Promise.resolve(promise).then(clearPending, clearPending);
    return promise;
  };

  ctx.clearPlaybackStallRecovery = function clearPlaybackStallRecovery() {
    window.clearTimeout(ctx.playbackStallTimer);
    ctx.playbackStallTimer = 0;
  };

  ctx.schedulePlaybackStallRecovery = function schedulePlaybackStallRecovery(event) {
    const media = ctx.currentPlaybackElement();
    const track = ctx.activeTrack.value;
    const playbackExpected = ctx.loading.value || ctx.isPlaying.value || !media?.paused;
    if (!media || !track?.id || !playbackExpected || media.ended) return;

    const requestId = ctx.playbackStallRequest + 1;
    const stalledAt = media.currentTime || ctx.currentTime.value || 0;
    const trackId = track.id;
    ctx.playbackStallRequest = requestId;
    ctx.clearPlaybackStallRecovery();
    ctx.playbackStallTimer = window.setTimeout(() => {
      ctx.recoverFromPlaybackStall({ requestId, trackId, stalledAt, target: event.target });
    }, 4_000);
  };

  ctx.recoverFromPlaybackStall = function recoverFromPlaybackStall({ requestId, trackId, stalledAt, target }) {
    const media = ctx.currentPlaybackElement();
    const track = ctx.activeTrack.value;
    if (
      requestId !== ctx.playbackStallRequest ||
      target !== media ||
      !ctx.buffering.value ||
      !track ||
      track.id !== trackId ||
      (!ctx.loading.value && !ctx.isPlaying.value && media.paused) ||
      media.ended ||
      media.currentTime > stalledAt + 0.5
    ) return;

    if (ctx.activeTrackIsVideo.value) {
      if (!track.streamRefreshTried && ctx.retryVideoStream(track, { refreshStream: true, resumeAt: stalledAt })) return;
      if (ctx.retryVideoStream(track, { avoidCurrentFormat: true, resumeAt: stalledAt })) return;
    } else {
      if (!track.streamRefreshTried && ctx.retryAudioStream(track, { refreshStream: true, resumeAt: stalledAt })) return;
      if (ctx.retryAudioWithAlternateFormat(track, { resumeAt: stalledAt })) return;
    }

    ctx.buffering.value = false;
    ctx.isPlaying.value = false;
    ctx.playbackError.value = 'Playback stalled while loading this stream.';
  };

  ctx.onAudioError = async function onAudioError(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    ctx.clearPlaybackStallRecovery();
    ctx.isSeeking.value = false;
    ctx.buffering.value = false;
    ctx.isPlaying.value = false;
    const track = ctx.activeTrack.value;
    const media = ctx.currentPlaybackElement();
    const error = media?.error;
    const shouldTryAlternateFirst = isSourceFormatError(error);
    const resumeAt = media?.currentTime || ctx.currentTime.value || 0;
    if (ctx.activeTrackIsVideo.value) {
      if (shouldTryAlternateFirst && ctx.retryVideoStream(track, { avoidCurrentFormat: true, resumeAt })) return;
      if (!track?.streamRefreshTried && ctx.retryVideoStream(track, { refreshStream: true, resumeAt })) return;
      if (ctx.retryVideoStream(track, { avoidCurrentFormat: true, resumeAt })) return;
    } else {
      if (shouldTryAlternateFirst && ctx.retryAudioWithAlternateFormat(track, { avoidCurrentMimeType: true, resumeAt })) return;
      if (!track?.streamRefreshTried && ctx.retryAudioStream(track, { refreshStream: true, resumeAt })) return;
      if (ctx.retryAudioWithAlternateFormat(track, { resumeAt })) return;
    }

    const code = error?.code ? `Media error ${error.code}` : 'Media error';
    const baseMessage = error?.message || `${code}: this stream format is not playable`;
    const formatDetail = track
      ? [track.mimeType, track.itag ? `itag ${track.itag}` : ''].filter(Boolean).join(', ')
      : '';
    const streamDetail = await ctx.describeStreamFailure(media?.currentSrc || media?.src);
    const details = [formatDetail, streamDetail].filter(Boolean).join('. ');
    ctx.playbackError.value = details ? `${baseMessage}. ${details}` : baseMessage;
    ctx.clearMediaElement(media);
  };
}
