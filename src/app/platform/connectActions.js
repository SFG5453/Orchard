const CONNECT_SYNC_INTERVAL_MS = 500;

function safeTrack(track = {}) {
  return {
    id: track.id || '',
    title: track.title || '',
    artist: track.artist || track.subtitle || track.artists?.[0] || '',
    album: track.album || '',
    thumbnail: track.thumbnail || ''
  };
}

function connectTrack(track = {}) {
  return {
    ...safeTrack(track),
    playbackItem: {
      ...track,
      artist: track.artist || track.artists?.[0] || track.subtitle || '',
      artists: track.artists?.length
        ? track.artists
        : [track.artist || track.subtitle].filter(Boolean)
    }
  };
}

function playableSearchItems(result = {}) {
  return (result.sections || [])
    .flatMap((section) => section.items || [])
    .filter((item) => item?.id && (item.type === 'song' || item.type === 'video'))
    .slice(0, 20)
    .map(connectTrack);
}

function mergeConnectState(ctx, state = {}) {
  ctx.orchardConnect.value = {
    ...ctx.orchardConnect.value,
    ...state,
    pending: state.pending || ctx.orchardConnect.value.pending || [],
    devices: state.devices || ctx.orchardConnect.value.devices || []
  };
}

export function installConnectActions(ctx) {
  let lastConnectSyncAt = 0;

  ctx.connectSnapshot = function connectSnapshot() {
    const track = ctx.activeTrack.value;
    return {
      status: ctx.socketState.value,
      track: track
        ? {
          ...safeTrack(track),
          artwork: ctx.nowArtworkImage.value || ctx.trackCover(track),
          animatedArtwork: ctx.nowArtworkVideo.value || ''
        }
        : null,
      playback: {
        isPlaying: ctx.isPlaying.value,
        buffering: ctx.buffering.value,
        currentTime: ctx.currentTime.value,
        duration: ctx.duration.value || track?.durationSeconds || 0,
        volume: ctx.volume.value
      },
      lyrics: {
        status: ctx.lyricsState.value.status,
        mode: ctx.lyricsState.value.mode,
        lines: (ctx.lyricsState.value.lines || []).slice(0, 120)
      },
      queue: ctx.queue.value.slice(0, 30).map(safeTrack),
      audioEngine: {
        config: ctx.audioEngineConfig?.value || {},
        activePreset: ctx.audioEngineActivePreset?.value || 'flat',
        presets: ctx.audioEnginePresets || []
      }
    };
  };

  ctx.syncConnectState = function syncConnectState() {
    window.clearTimeout(ctx.orchardConnectSyncTimer);
    ctx.orchardConnectSyncTimer = 0;
    lastConnectSyncAt = Date.now();
    if (!ctx.socket.value?.connected) return;
    ctx.socket.value.emit('connect:desktop-state', ctx.connectSnapshot());
  };

  ctx.queueConnectSync = function queueConnectSync() {
    if (!ctx.socket.value?.connected) return;
    if (ctx.orchardConnectSyncTimer) return;

    const elapsed = Date.now() - lastConnectSyncAt;
    const delay = Math.max(0, CONNECT_SYNC_INTERVAL_MS - elapsed);
    ctx.orchardConnectSyncTimer = window.setTimeout(ctx.syncConnectState, delay);
  };

  ctx.loadOrchardConnectInfo = async function loadOrchardConnectInfo({ refresh = false } = {}) {
    if (!ctx.socket.value?.connected) return;
    const event = refresh ? 'connect:pairing-refresh' : 'connect:pairing-info';
    const data = await ctx.emitWithReply(event);
    mergeConnectState(ctx, refresh
      ? {
        pairUrl: data.appUrl || data.url,
        appPairUrl: data.appUrl || data.url,
        webPairUrl: data.webUrl || '',
        qrSvg: data.qrSvg,
        expiresAt: data.expiresAt
      }
      : data);
  };

  ctx.approveOrchardConnectPairing = async function approveOrchardConnectPairing(id) {
    if (!ctx.socket.value?.connected || !id) return;
    await ctx.emitWithReply('connect:pairing-approve', { id });
    ctx.orchardConnectPairingMessage.value = 'Phone approved.';
  };

  ctx.rejectOrchardConnectPairing = async function rejectOrchardConnectPairing(id) {
    if (!ctx.socket.value?.connected || !id) return;
    await ctx.emitWithReply('connect:pairing-reject', { id });
    ctx.orchardConnectPairingMessage.value = 'Pairing rejected.';
  };

  ctx.revokeOrchardConnectDevice = async function revokeOrchardConnectDevice(id) {
    if (!ctx.socket.value?.connected || !id) return;
    await ctx.emitWithReply('connect:device-revoke', { id });
    ctx.orchardConnectPairingMessage.value = 'Phone access revoked.';
  };

  ctx.copyOrchardConnectLink = async function copyOrchardConnectLink() {
    const url = ctx.orchardConnect.value.pairUrl;
    if (!url || !navigator.clipboard) return;
    await navigator.clipboard.writeText(url);
    ctx.orchardConnectPairingMessage.value = 'App link copied.';
  };

  ctx.copyOrchardConnectWebLink = async function copyOrchardConnectWebLink() {
    const url = ctx.orchardConnect.value.webPairUrl;
    if (!url || !navigator.clipboard) return;
    await navigator.clipboard.writeText(url);
    ctx.orchardConnectPairingMessage.value = 'Camera link copied.';
  };

  ctx.handleConnectCommand = function handleConnectCommand({ command = {} } = {}) {
    const type = command.type;
    if (type === 'play-pause') ctx.togglePlayback();
    else if (type === 'play' && !ctx.isPlaying.value) ctx.togglePlayback();
    else if (type === 'pause' && ctx.isPlaying.value) ctx.togglePlayback();
    else if (type === 'next') void ctx.playNext({ skipRepeatOne: true });
    else if (type === 'previous') ctx.playPrevious();
    else if (type === 'volume') ctx.volume.value = Math.max(0, Math.min(1, Number(command.value) || 0));
    else if (type === 'seek') ctx.seek(Number(command.value) || 0);
    else if (type === 'audio-engine-preset') ctx.applyAudioEnginePreset(command.value);
    else if (type === 'audio-engine-auto-eq') ctx.setAutoEqEnabled(Boolean(command.value));
    else if (type === 'audio-engine-manual-eq') ctx.setManualEqEnabled(Boolean(command.value));
    else if (type === 'play-queue-index') {
      const index = Number(command.value);
      const track = Number.isInteger(index) ? ctx.queue.value[index] : null;
      if (track) ctx.playTrack(track, { queueSource: ctx.queue.value });
    } else if (type === 'remove-queue-index') {
      const index = Number(command.value);
      if (Number.isInteger(index)) ctx.removeQueueTrack(index);
    } else if (type === 'play-track' && command.value?.id) {
      const track = command.value.playbackItem || command.value;
      ctx.playTrack(track, { queueSource: [track, ...ctx.queue.value] });
    }
  };

  ctx.handleConnectSearch = async function handleConnectSearch({ deviceId, query, requestId } = {}) {
    if (!deviceId || !query) return;
    try {
      const result = await ctx.emitWithReply('music:search', { query, filter: 'songs' });
      ctx.socket.value.emit('connect:remote-search-results', {
        deviceId,
        requestId,
        results: playableSearchItems(result)
      });
    } catch {
      ctx.socket.value.emit('connect:remote-search-results', { deviceId, requestId, results: [] });
    }
  };

  ctx.handleConnectLibrary = function handleConnectLibrary({ deviceId, requestId } = {}) {
    if (!deviceId) return;
    const librarySections = ctx.homeData?.value?.library?.sections || [];
    const results = librarySections
      .flatMap((section) => section.items || [])
      .filter((item) => item?.id && (item.type === 'song' || item.type === 'video' || item.type === 'playlist' || item.type === 'album'))
      .map(connectTrack);
    
    ctx.socket.value.emit('connect:remote-library-results', {
      deviceId,
      requestId,
      results
    });
  };

  ctx.bindOrchardConnectEvents = function bindOrchardConnectEvents() {
    if (ctx.orchardConnectEventsBound) return;
    ctx.orchardConnectEventsBound = true;

    ctx.socket.value.on('connect:pairing-request', (request) => {
      mergeConnectState(ctx, {
        pending: [
          request,
          ...ctx.orchardConnect.value.pending.filter((item) => item.id !== request.id)
        ]
      });
      ctx.orchardConnectPairingMessage.value = `${request.name || 'Phone'} wants to control Orchard.`;
    });
    ctx.socket.value.on('connect:pairing-state', (state) => mergeConnectState(ctx, state));
    ctx.socket.value.on('connect:remote-command', ctx.handleConnectCommand);
    ctx.socket.value.on('connect:remote-search', (payload) => void ctx.handleConnectSearch(payload));
    ctx.socket.value.on('connect:remote-library', (payload) => void ctx.handleConnectLibrary(payload));
  };
}
