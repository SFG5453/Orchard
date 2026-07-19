import { onBeforeUnmount, ref, watch } from 'vue';

const FALLBACK_VOLUME = 0.55;

function normalizePreviewTrack(track = {}, config = {}) {
  const id = String(track.id || track.videoId || '').trim();
  if (!id) return null;

  const artistName = config.displayName || config.artistName || '';
  return {
    id,
    title: track.title || '',
    artist: track.artist || artistName,
    artists: track.artists?.length ? track.artists : [track.artist || artistName].filter(Boolean),
    album: track.album || '',
    thumbnail: track.thumbnail || `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
    type: track.type || 'song',
    mediaKind: 'audio',
    isAudioOnly: true,
    start: Math.max(0, Number(track.start || 0) || 0)
  };
}

function previewTracks(config = {}) {
  const tracks = Array.isArray(config.idlePreview?.tracks) ? config.idlePreview.tracks : [];
  return tracks
    .map((track) => normalizePreviewTrack(track, config))
    .filter(Boolean);
}

function previewPayload(app, track) {
  return {
    videoId: track.id,
    originalVideoId: track.id,
    title: track.title || '',
    artist: track.artist || '',
    artists: track.artists || [],
    album: track.album || '',
    thumbnail: track.thumbnail || '',
    durationSeconds: 0,
    explicit: false,
    type: 'song',
    musicVideoType: '',
    isAudioOnly: true,
    mediaKind: 'audio',
    preload: true,
    refreshStream: false,
    avoidItags: [],
    avoidMimeTypes: [],
    preferAudioOnly: true,
    supportedMimes: app.supportedAudioMimes?.() || [],
    supportedVideoMimes: []
  };
}

function previewVolume(value) {
  const volume = Number(value);
  return Math.min(Number.isFinite(volume) ? Math.max(0, volume) : FALLBACK_VOLUME, FALLBACK_VOLUME);
}

export function installCustomArtistIdlePreviewActions(ctx) {
  let audio = null;
  let config = null;
  let requestId = 0;
  let trackIndex = -1;
  const positions = new Map();

  ctx.customArtistIdlePreview = ref({
    visible: false,
    status: 'idle',
    muted: false,
    artistId: '',
    artistName: '',
    track: null,
    error: ''
  });

  function patchPreview(patch) {
    ctx.customArtistIdlePreview.value = {
      ...ctx.customArtistIdlePreview.value,
      ...patch
    };
  }

  function ensureAudio() {
    if (!audio) {
      audio = new Audio();
      audio.crossOrigin = 'anonymous';
      audio.preload = 'auto';
      audio.onended = () => {
        if (canPreview()) void startPreview({ advance: true });
      };
      audio.onerror = () => stopPreview();
    }

    return audio;
  }

  function canPreview() {
    const detail = ctx.browseDetail.value;
    return Boolean(
      config?.tracks?.length &&
      ctx.customArtistPagesEnabled?.value !== false &&
      ctx.activeView.value === 'browse' &&
      detail?.kind === 'artist' &&
      detail.browseId === config.artistId &&
      !ctx.isPlaying.value &&
      ctx.socketState.value === 'connected'
    );
  }

  function selectedTrack(advance = false) {
    if (!config?.tracks?.length) return null;
    trackIndex = advance ? trackIndex + 1 : Math.max(trackIndex, 0);
    return config.tracks[trackIndex % config.tracks.length];
  }

  function positionKey(track = ctx.customArtistIdlePreview.value.track) {
    const artistId = config?.artistId || ctx.customArtistIdlePreview.value.artistId;
    return artistId && track?.id ? `${artistId}:${track.id}` : '';
  }

  function savePosition() {
    const key = positionKey();
    if (!key || !audio || audio.ended) return;

    const currentTime = Number(audio.currentTime || 0);
    if (Number.isFinite(currentTime) && currentTime > 0) {
      positions.set(key, currentTime);
    }
  }

  function resumeTime(track) {
    const savedTime = positions.get(positionKey(track));
    return Math.max(track.start, Number.isFinite(savedTime) ? savedTime : track.start);
  }

  function stopPreview({ save = true } = {}) {
    requestId += 1;
    if (save) savePosition();
    if (audio) {
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
    }
    patchPreview({ visible: false, status: 'idle', track: null, error: '' });
  }

  async function startPreview({ advance = false } = {}) {
    if (!canPreview()) {
      stopPreview();
      return;
    }

    const track = selectedTrack(advance);
    if (!track) return;

    const currentRequest = ++requestId;
    patchPreview({
      visible: true,
      status: 'loading',
      artistId: config.artistId,
      artistName: config.artistName,
      track,
      error: ''
    });

    try {
      const resolved = await ctx.emitWithReply('music:track', previewPayload(ctx, track));
      if (currentRequest !== requestId || !canPreview()) return;

      const mergedTrack = {
        ...track,
        ...resolved,
        id: resolved.id || track.id,
        title: resolved.title || track.title || config.artistName,
        artist: resolved.artist || track.artist || config.artistName,
        thumbnail: track.thumbnail || resolved.thumbnail
      };
      const element = ensureAudio();
      element.src = resolved.streamUrl;
      element.currentTime = resumeTime(track);
      element.muted = ctx.customArtistIdlePreview.value.muted;
      element.volume = previewVolume(ctx.volume.value);
      patchPreview({ status: 'playing', track: mergedTrack });

      await element.play().catch(async (error) => {
        if (!element.muted && error?.name === 'NotAllowedError') {
          element.muted = true;
          patchPreview({ muted: true });
          await element.play();
          return;
        }
        throw error;
      });
    } catch (error) {
      if (currentRequest !== requestId) return;
      patchPreview({
        visible: true,
        status: 'error',
        error: error.message || 'Artist preview could not start.'
      });
    }
  }

  function syncPreview() {
    if (canPreview()) {
      if (!audio?.src && ctx.customArtistIdlePreview.value.status !== 'loading') void startPreview();
      return;
    }

    stopPreview();
  }

  ctx.setCustomArtistIdlePreview = function setCustomArtistIdlePreview(artistConfig) {
    config = {
      artistId: artistConfig.artistId,
      artistName: artistConfig.displayName || artistConfig.artistName || '',
      tracks: previewTracks(artistConfig)
    };
    trackIndex = -1;
    syncPreview();

    return () => {
      if (config?.artistId !== artistConfig.artistId) return;
      config = null;
      trackIndex = -1;
      stopPreview();
    };
  };

  ctx.toggleCustomArtistIdlePreviewMute = function toggleCustomArtistIdlePreviewMute() {
    const muted = !ctx.customArtistIdlePreview.value.muted;
    if (audio) audio.muted = muted;
    patchPreview({ muted });
  };

  ctx.stopCustomArtistIdlePreview = function stopCustomArtistIdlePreview() {
    stopPreview();
  };

  const stateWatcher = watch(() => [
    ctx.activeView.value,
    ctx.browseDetail.value?.browseId || '',
    ctx.browseDetail.value?.kind || '',
    ctx.activeTrack.value?.id || '',
    ctx.isPlaying.value,
    ctx.socketState.value,
    ctx.customArtistPagesEnabled?.value !== false
  ], syncPreview);

  const volumeWatcher = watch(ctx.volume, (value) => {
    if (audio) audio.volume = previewVolume(value);
  });

  onBeforeUnmount(() => {
    stateWatcher();
    volumeWatcher();
    stopPreview();
    audio = null;
  });
}
