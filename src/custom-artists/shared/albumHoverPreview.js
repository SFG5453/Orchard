import { watch } from 'vue';

const DEFAULT_RETRY_LIMIT = 20;
const DEFAULT_RETRY_MS = 300;
const DEFAULT_FADE_IN_MS = 600;
const DEFAULT_FADE_OUT_MS = 400;

function sectionForElement(app, sectionElement) {
  const headerText = sectionElement.querySelector('.section-header h2')?.textContent?.trim();
  if (!headerText) return null;
  return (app.browseDetailSections?.value || []).find((section) => section.title === headerText) || null;
}

function albumBrowseId(app, item) {
  return app.itemBrowseId?.(item) || item?.browseId || item?.browsePayload?.browseId || '';
}

function fadeVolume(state, audio, from, to, durationMs) {
  cancelAnimationFrame(state.fadeTimer);
  const startTime = performance.now();
  audio.volume = from;

  return new Promise((resolve) => {
    function step(now) {
      const elapsed = now - startTime;
      const progress = Math.min(elapsed / durationMs, 1);
      const eased = progress * (2 - progress);
      audio.volume = from + (to - from) * eased;
      if (progress < 1) {
        state.fadeTimer = requestAnimationFrame(step);
      } else {
        resolve();
      }
    }
    state.fadeTimer = requestAnimationFrame(step);
  });
}

function previewTrackPayload(app, videoId) {
  return {
    videoId,
    originalVideoId: videoId,
    title: '',
    artist: '',
    artists: [],
    album: '',
    thumbnail: '',
    durationSeconds: 0,
    explicit: false,
    type: 'song',
    musicVideoType: '',
    isAudioOnly: false,
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

export function attachAlbumHoverPreviews(app, {
  artistId,
  previews,
  retryLimit = DEFAULT_RETRY_LIMIT,
  retryMs = DEFAULT_RETRY_MS,
  fadeInMs = DEFAULT_FADE_IN_MS,
  fadeOutMs = DEFAULT_FADE_OUT_MS
}) {
  const listeners = [];
  const state = {
    audio: null,
    fadeTimer: null,
    stopTimer: null,
    resolveAbort: null,
    activePreviewId: null,
    stopVersion: 0
  };
  let retryTimer = null;
  let attempts = 0;
  let disposed = false;

  function ensureAudio() {
    if (!state.audio) {
      state.audio = new Audio();
      state.audio.crossOrigin = 'anonymous';
    }
    state.audio.volume = 0;
    return state.audio;
  }

  function stopPreview() {
    const audio = state.audio;
    const stopVersion = ++state.stopVersion;
    state.activePreviewId = null;
    clearTimeout(state.stopTimer);

    if (state.resolveAbort) {
      state.resolveAbort.abort();
      state.resolveAbort = null;
    }

    if (audio && !audio.paused) {
      fadeVolume(state, audio, audio.volume, 0, fadeOutMs).then(() => {
        if (state.stopVersion !== stopVersion) return;
        audio.pause();
        audio.src = '';
      });
    } else if (audio) {
      audio.pause();
      audio.src = '';
    }

  }

  async function startPreview(browseId) {
    const config = previews[browseId];
    if (!config || state.activePreviewId === browseId) return;

    const media = app.currentPlaybackElement?.();
    if (
      app.isPlaying?.value ||
      (media?.src && !media.paused) ||
      app.customArtistIdlePreview?.value?.visible
    ) {
      return;
    }

    stopPreview();
    state.activePreviewId = browseId;
    state.stopVersion += 1;

    const audio = ensureAudio();
    const abort = new AbortController();
    state.resolveAbort = abort;

    try {
      const resolved = await app.emitWithReply('music:track', previewTrackPayload(app, config.videoId));
      if (abort.signal.aborted || state.activePreviewId !== browseId) return;

      audio.src = resolved.streamUrl;
      audio.currentTime = typeof config.start === 'number' ? config.start : 0;

      await audio.play().catch(() => {});
      if (abort.signal.aborted || state.activePreviewId !== browseId) {
        audio.pause();
        return;
      }

      const targetVol = typeof app.volume?.value === 'number' ? app.volume.value : 0.55;
      fadeVolume(state, audio, 0, targetVol, fadeInMs);

      state.stopTimer = setTimeout(() => {
        if (state.activePreviewId === browseId) stopPreview();
      }, (typeof config.duration === 'number' ? config.duration : 30) * 1000);
    } catch {
      if (state.activePreviewId === browseId) stopPreview();
    }
  }

  function detach() {
    for (const { card, onEnter, onLeave } of listeners) {
      card.removeEventListener('mouseenter', onEnter);
      card.removeEventListener('mouseleave', onLeave);
    }
    listeners.length = 0;
  }

  function attach() {
    if (disposed) return;
    clearTimeout(retryTimer);
    detach();

    const page = document.querySelector(`.detail-page--artist[data-artist-id="${artistId}"]`);
    if (!page) {
      if (attempts++ < retryLimit) retryTimer = setTimeout(attach, retryMs);
      return;
    }

    const sections = page.querySelectorAll('.shelf-section');
    for (const sectionElement of sections) {
      const section = sectionForElement(app, sectionElement);
      const items = section?.items || [];
      if (!items.length) continue;

      const cards = sectionElement.querySelectorAll('.media-rail > .media-card');
      cards.forEach((card, index) => {
        const browseId = albumBrowseId(app, items[index]);
        if (!browseId || !previews[browseId]) return;

        const onEnter = () => startPreview(browseId);
        const onLeave = () => stopPreview();

        card.addEventListener('mouseenter', onEnter);
        card.addEventListener('mouseleave', onLeave);
        listeners.push({ card, onEnter, onLeave });
      });
    }

    if (!listeners.length && attempts++ < retryLimit) {
      retryTimer = setTimeout(attach, retryMs);
    }
  }

  const volumeWatcher = watch(app.volume, (newVol) => {
    if (state.audio && state.activePreviewId) {
      state.audio.volume = newVol;
    }
  });

  const playbackWatcher = watch(app.isPlaying, (playing) => {
    if (playing) stopPreview();
  });

  const sectionWatcher = watch(app.browseDetailSections, () => {
    attempts = 0;
    clearTimeout(retryTimer);
    retryTimer = setTimeout(attach, 0);
  }, { flush: 'post' });

  attach();

  return () => {
    disposed = true;
    volumeWatcher();
    playbackWatcher();
    sectionWatcher();
    clearTimeout(retryTimer);
    stopPreview();
    detach();
    cancelAnimationFrame(state.fadeTimer);
    if (state.audio) {
      state.audio.pause();
      state.audio.src = '';
      state.audio = null;
    }
  };
}
