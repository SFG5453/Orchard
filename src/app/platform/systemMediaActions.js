function systemMediaBridge() {
  return typeof window === 'undefined' ? null : window.orchardSystemMedia;
}

function stringValue(value) {
  return value == null ? '' : String(value);
}

function numberValue(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function stringList(value) {
  if (!Array.isArray(value)) return [];
  return value.map(stringValue).filter(Boolean);
}

export function installSystemMediaActions(ctx) {
  ctx.systemMediaPayload = function systemMediaPayload() {
    const track = ctx.activeTrack.value;
    const artists = stringList(track?.artists);

    return {
      track: track ? {
        id: stringValue(track.id),
        title: stringValue(track.title),
        artist: stringValue(ctx.activeArtist.value || track.artist || artists[0]),
        artists,
        album: stringValue(track.album),
        thumbnail: stringValue(ctx.nowArtworkImage.value || track.thumbnail)
      } : null,
      isPlaying: Boolean(ctx.isPlaying.value && !ctx.buffering.value),
      canGoNext: Boolean(ctx.queue.value.length || ctx.repeatMode.value !== 'off'),
      canGoPrevious: Boolean(track),
      canSeek: Boolean(track && !ctx.activeTrackIsLive.value && ctx.duration.value > 0),
      currentTime: Math.max(0, numberValue(ctx.displayedTime.value || ctx.currentTime.value)),
      durationSeconds: numberValue(ctx.duration.value || track?.durationSeconds),
      volume: numberValue(ctx.volume.value, 1),
      repeatMode: stringValue(ctx.repeatMode.value),
      shuffleEnabled: ctx.shuffleEnabled.value
    };
  };

  ctx.syncSystemMediaState = function syncSystemMediaState() {
    const bridge = systemMediaBridge();
    ctx.syncDesktopControlsState?.();
    if (!bridge) return;

    try {
      bridge.setState(ctx.systemMediaPayload())?.catch?.(() => {});
    } catch {
      // IPC payload cloning can fail if a future field accidentally carries a proxy.
    }
  };

  let lastSyncAt = 0;
  ctx.queueSystemMediaSync = function queueSystemMediaSync() {
    if (ctx.systemMediaSyncTimer) return;
    const delay = Math.max(0, 180 - (Date.now() - lastSyncAt));
    ctx.systemMediaSyncTimer = window.setTimeout(() => {
      ctx.systemMediaSyncTimer = 0;
      lastSyncAt = Date.now();
      ctx.syncSystemMediaState();
    }, delay);
  };

  ctx.handleSystemMediaCommand = function handleSystemMediaCommand(command = {}) {
    switch (command.type) {
      case 'play':
        if (!ctx.isPlaying.value) ctx.togglePlayback();
        break;
      case 'pause':
        if (ctx.isPlaying.value) ctx.togglePlayback();
        break;
      case 'play-pause':
        ctx.togglePlayback();
        break;
      case 'stop':
        if (ctx.isPlaying.value) ctx.togglePlayback();
        ctx.seek(0);
        break;
      case 'previous':
        if (ctx.activeTrack.value && !ctx.buffering.value) ctx.playPrevious();
        break;
      case 'next':
        if ((ctx.queue.value.length || ctx.repeatMode.value !== 'off') && !ctx.buffering.value) {
          ctx.playNext({ skipRepeatOne: true });
        }
        break;
      case 'seek':
        ctx.seek(command.value);
        break;
      case 'seek-relative':
        ctx.seekRelative(command.value);
        break;
      case 'set-volume':
        ctx.volume.value = command.value;
        break;
      case 'set-shuffle':
        if (Boolean(command.value) !== ctx.shuffleEnabled.value) ctx.toggleShuffle();
        break;
      case 'set-repeat-mode':
        ctx.repeatMode.value = command.value;
        break;
      default:
        break;
    }
  };

  ctx.bindSystemMediaEvents = function bindSystemMediaEvents() {
    ctx.systemMediaUnsubscribe = systemMediaBridge()?.onCommand(ctx.handleSystemMediaCommand) || null;
    ctx.syncSystemMediaState();
  };

  ctx.clearSystemMediaEvents = function clearSystemMediaEvents() {
    window.clearTimeout(ctx.systemMediaSyncTimer);
    ctx.systemMediaUnsubscribe?.();
    ctx.systemMediaUnsubscribe = null;
  };
}
