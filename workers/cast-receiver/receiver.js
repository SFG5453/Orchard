/**
 * Orchard Custom Google Cast Web Receiver
 * Cast audio with animated cover art and a low-resolution Kawarp backdrop.
 */

(() => {
  'use strict';

  // DOM Elements
  const idleView = document.getElementById('idle-view');
  const playerView = document.getElementById('player-view');

  const ambientArt = document.getElementById('ambient-art');
  const ambientWarp = document.getElementById('ambient-warp');

  const artworkImg = document.getElementById('artwork-img');
  const artworkVideo = document.getElementById('artwork-video');

  const trackTitle = document.getElementById('track-title');
  const trackArtist = document.getElementById('track-artist');
  const trackAlbum = document.getElementById('track-album');
  const playbackIndicator = document.getElementById('playback-indicator');

  const progressFill = document.getElementById('progress-fill');
  const currentTimeLabel = document.getElementById('current-time');
  const totalTimeLabel = document.getElementById('total-time');

  let currentMediaState = {
    contentId: '',
    title: '',
    artist: '',
    album: '',
    artworkUrl: '',
    animatedArtworkUrl: null,
    hasAnimatedArt: false,
    duration: 0,
  };
  let artworkRequest = null;
  let artworkGeneration = 0;
  let artworkHls = null;
  let playbackState = 'BUFFERING';
  let kawarpRenderer = null;
  let kawarpUnavailable = false;
  let kawarpGeneration = 0;
  let kawarpLoading = Promise.resolve();
  let kawarpSource = '';
  const reducedMotion = typeof window !== 'undefined' &&
    (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || false);

  /**
   * Format seconds to m:ss or h:mm:ss string
   */
  function formatTime(seconds) {
    if (!seconds || isNaN(seconds) || seconds < 0) return '0:00';
    const s = Math.floor(seconds);
    const hrs = Math.floor(s / 3600);
    const mins = Math.floor((s % 3600) / 60);
    const secs = s % 60;
    const secPad = secs < 10 ? '0' + secs : secs;
    if (hrs > 0) {
      const minPad = mins < 10 ? '0' + mins : mins;
      return `${hrs}:${minPad}:${secPad}`;
    }
    return `${mins}:${secPad}`;
  }

  // The backdrop is intentionally rendered at a quarter of the TV's CSS size.
  // Blur hides the pixels, while the small WebGL surface keeps TV GPU use low.
  function resizeKawarp() {
    if (!kawarpRenderer) return;
    const width = Math.max(2, window.innerWidth * 0.25);
    const height = Math.max(2, window.innerHeight * 0.25);
    const scale = Math.min(1, Math.sqrt((640 * 360) / (width * height)));
    const targetWidth = Math.max(2, Math.round(width * scale));
    const targetHeight = Math.max(2, Math.round(height * scale));
    if (ambientWarp.width === targetWidth && ambientWarp.height === targetHeight) return;
    ambientWarp.width = targetWidth;
    ambientWarp.height = targetHeight;
    kawarpRenderer.resize();
    if (kawarpSource) kawarpRenderer.renderFrame(0);
  }

  function syncKawarpPlayback() {
    if (!kawarpRenderer || !kawarpSource) return;
    if (playbackState === 'PLAYING' && !reducedMotion) {
      kawarpRenderer.start();
    } else {
      kawarpRenderer.stop();
      kawarpRenderer.renderFrame(0);
    }
  }

  function setKawarpArtwork(url) {
    const generation = ++kawarpGeneration;
    kawarpSource = url || '';
    ambientWarp.classList.remove('active');
    kawarpRenderer?.stop();
    if (!url || kawarpUnavailable || typeof KawarpCore === 'undefined') return;

    if (!kawarpRenderer) {
      try {
        ambientWarp.width = 2;
        ambientWarp.height = 2;
        kawarpRenderer = new KawarpCore.Kawarp(ambientWarp, {
          animationSpeed: 0.85,
          blurPasses: 4,
          saturation: 1.2,
          scale: 1.2,
          transitionDuration: 800,
          warpIntensity: 0.8,
        });
        resizeKawarp();
      } catch (error) {
        kawarpUnavailable = true;
        console.warn('Kawarp is unavailable on this receiver:', error);
        return;
      }
    }

    // Image loads cannot be cancelled. Serialize them so an old track cannot
    // finish after a newer one and replace its background.
    kawarpLoading = kawarpLoading.catch(() => {}).then(async () => {
      if (generation !== kawarpGeneration) return;
      await kawarpRenderer.loadImage(url);
      if (generation !== kawarpGeneration) return;
      ambientWarp.classList.add('active');
      syncKawarpPlayback();
    }).catch(error => {
      if (generation === kawarpGeneration) {
        console.warn('Kawarp could not load the artwork:', error);
      }
    });
  }

  /**
   * Switch between screens (idle vs player)
   */
  function showScreen(screen) {
    if (screen === 'player') {
      idleView.classList.remove('active');
      playerView.classList.add('active');
    } else {
      playerView.classList.remove('active');
      idleView.classList.add('active');
      resetMediaUI();
    }
  }

  /**
   * Reset the player elements when returning to idle
   */
  function resetMediaUI() {
    artworkGeneration++;
    artworkRequest?.abort();
    artworkRequest = null;
    tearDownAnimatedArtwork();
    setKawarpArtwork('');

    artworkImg.src = '';
    artworkImg.classList.remove('active');
    ambientArt.style.backgroundImage = 'none';
    ambientArt.classList.remove('active');

    trackTitle.textContent = '--';
    trackArtist.textContent = '--';
    trackAlbum.textContent = '--';
    progressFill.style.width = '0%';
    currentTimeLabel.textContent = '0:00';
    totalTimeLabel.textContent = '0:00';

    currentMediaState = {
      contentId: '',
      title: '',
      artist: '',
      album: '',
      artworkUrl: '',
      animatedArtworkUrl: null,
      hasAnimatedArt: false,
      duration: 0,
    };
  }

  /**
   * Update the metadata & artwork displays
   */
  function applyMediaData(mediaInfo) {
    if (!mediaInfo) return;

    artworkGeneration++;
    artworkRequest?.abort();
    artworkRequest = null;
    tearDownAnimatedArtwork();

    const customData = mediaInfo.customData || {};
    const metadata = mediaInfo.metadata || {};

    const title = metadata.title || customData.title || 'Unknown Title';
    const artist = metadata.artist || metadata.subtitle || customData.artist || '';
    const album = metadata.albumName || metadata.albumTitle || customData.album || '';

    const artworkUrl = (metadata.images && metadata.images[0] && metadata.images[0].url) ||
      customData.artworkUrl || '';

    const animatedUrl = customData.animatedArtworkUrl || customData.animatedArtworkVerticalUrl || null;
    currentMediaState.contentId = mediaInfo.contentId || mediaInfo.contentUrl || '';
    currentMediaState.title = title;
    currentMediaState.artist = artist;
    currentMediaState.album = album;
    currentMediaState.artworkUrl = artworkUrl;
    currentMediaState.animatedArtworkUrl = animatedUrl;
    currentMediaState.hasAnimatedArt = Boolean(animatedUrl);

    // Update text metadata
    trackTitle.textContent = title;
    trackArtist.textContent = artist;
    trackAlbum.textContent = album;

    // Handle Static Artwork
    if (artworkUrl) {
      artworkImg.src = artworkUrl;
      artworkImg.classList.add('active');

      ambientArt.style.backgroundImage = `url("${artworkUrl}")`;
      ambientArt.classList.add('active');
      setKawarpArtwork(artworkUrl);
    } else {
      artworkImg.src = '';
      artworkImg.classList.remove('active');
      ambientArt.style.backgroundImage = 'none';
      ambientArt.classList.remove('active');
      setKawarpArtwork('');
    }

    // The sender's queue item may have motion already. Most tracks acquire it later
    // on the phone, so the receiver also resolves artwork for the active track.
    if (animatedUrl) {
      setupAnimatedArtwork(animatedUrl);
    }

    showScreen('player');
    if (!animatedUrl) resolveAnimatedArtwork(title, artist, album, artworkGeneration);
  }

  function normalize(value) {
    return String(value || '').toLowerCase().replace(/&/g, ' and ')
      .replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ');
  }

  function looseMatch(left, right) {
    const a = normalize(left);
    const b = normalize(right);
    return !a || !b || a === b || a.includes(b) || b.includes(a);
  }

  async function resolveAnimatedArtwork(title, artist, album, generation) {
    if (!title || !artist || title === 'Unknown Title') return;
    const controller = new AbortController();
    artworkRequest = controller;
    const providers = [
      {
        id: 'boidu',
        url: `https://artwork.boidu.dev/?${new URLSearchParams({ s: title, a: artist })}`,
      },
    ];
    if (album) {
      providers.push({
        id: 'm8tec',
        url: `https://artwork.m8tec.top/api/v1/artwork/search?${new URLSearchParams({ artist, album, title })}`,
      });
    }

    try {
      for (const provider of providers) {
        let data;
        try {
          const response = await fetch(provider.url, { signal: controller.signal });
          if (!response.ok) continue;
          data = await response.json();
        } catch (error) {
          if (error.name === 'AbortError') return;
          console.warn(`Animated artwork provider ${provider.id} failed:`, error);
          continue;
        }
        if (generation !== artworkGeneration) return;

        const accepted = provider.id === 'boidu'
          ? (normalize(data.name) === normalize(title) ||
            (album && normalize(data.name) === normalize(album))) && looseMatch(data.artist, artist)
          : looseMatch(data.artist, artist) && looseMatch(data.album, album);
        if (!accepted) continue;

        const url = provider.id === 'boidu'
          ? data.animated || data.animatedVertical || data.videoUrl || data.videoUrlVertical
          : data.url || data.url_tall;
        const fallbackUrl = provider.id === 'boidu'
          ? data.videoUrl || data.videoUrlVertical || null
          : null;
        if (typeof url === 'string' && /^https:\/\//i.test(url)) {
          setupAnimatedArtwork(url, fallbackUrl);
          return;
        }
      }
    } catch (error) {
      if (error.name !== 'AbortError') {
        console.warn('Animated artwork lookup failed:', error);
      }
    } finally {
      if (artworkRequest === controller) artworkRequest = null;
    }
  }

  function playArtworkVideo(video) {
    video.play().catch(error => {
      console.warn('Animated artwork video could not play:', error);
    });
  }

  /**
   * Set up and play animated artwork video
   */
  function setupAnimatedArtwork(url, fallbackUrl = null) {
    tearDownAnimatedArtwork();
    currentMediaState.animatedArtworkUrl = url;
    currentMediaState.hasAnimatedArt = true;

    const useHls = /\.m3u8(?:[?#]|$)/i.test(url);
    const canPlayHlsNatively = Boolean(artworkVideo.canPlayType('application/vnd.apple.mpegurl'));
    const canPlayHlsWithMse = typeof Hls !== 'undefined' && Hls.isSupported();
    if (useHls && !canPlayHlsNatively && !canPlayHlsWithMse) {
      if (fallbackUrl && fallbackUrl !== url) setupAnimatedArtwork(fallbackUrl);
      else tearDownAnimatedArtwork();
      return;
    }

    const handleError = (error) => {
      console.warn('Animated artwork video failed to load:', error);
      if (fallbackUrl && fallbackUrl !== url) setupAnimatedArtwork(fallbackUrl);
      else tearDownAnimatedArtwork();
    };

    artworkVideo.onerror = handleError;
    artworkVideo.onplaying = () => {
      artworkVideo.classList.add('active');
    };
    artworkVideo.oncanplay = () => {
      if (playbackState === 'PLAYING') playArtworkVideo(artworkVideo);
    };

    if (useHls && !canPlayHlsNatively) {
      const hls = new Hls({ capLevelToPlayerSize: true });
      artworkHls = hls;
      hls.on(Hls.Events.MEDIA_ATTACHED, () => {
        if (artworkHls === hls) hls.loadSource(url);
      });
      hls.on(Hls.Events.ERROR, (_, data) => {
        if (data.fatal) handleError(data);
      });
      hls.attachMedia(artworkVideo);
      // One HLS player is sufficient; Kawarp uses the still cover behind it.
    } else {
      artworkVideo.src = url;
      artworkVideo.load();
    }
    if (playbackState === 'PLAYING') playArtworkVideo(artworkVideo);
  }

  /**
   * Stop and remove animated video layers
   */
  function tearDownAnimatedArtwork() {
    if (artworkHls) {
      artworkHls.destroy();
      artworkHls = null;
    }
    artworkVideo.classList.remove('active');

    artworkVideo.pause();

    artworkVideo.onerror = null;
    artworkVideo.onplaying = null;
    artworkVideo.oncanplay = null;

    artworkVideo.removeAttribute('src');
    artworkVideo.load();

    currentMediaState.animatedArtworkUrl = null;
    currentMediaState.hasAnimatedArt = false;

    if (currentMediaState.artworkUrl) {
      ambientArt.classList.add('active');
    }
  }

  /**
   * Synchronize video play/pause with cast audio element
   */
  function setPlaybackState(state) {
    playbackState = state;
    playbackIndicator.className = `playback-indicator ${state.toLowerCase()}`;
    playbackIndicator.textContent = state.charAt(0) + state.slice(1).toLowerCase();
    syncKawarpPlayback();

    if (state === 'PLAYING') {
      if (currentMediaState.hasAnimatedArt) {
        if (artworkVideo.paused) artworkVideo.play().catch(() => {});
      }
    } else {
      artworkVideo.pause();
    }
  }

  // --------------------------------------------------------------------------
  // Cast Application Framework (CAF) v3 Initialization
  // --------------------------------------------------------------------------
  if (typeof cast === 'undefined' || !cast.framework) {
    console.warn('Cast framework SDK not found. Running in standalone preview mode.');
    return;
  }

  const context = cast.framework.CastReceiverContext.getInstance();
  const playerManager = context.getPlayerManager();
  if (typeof window !== 'undefined') window.addEventListener('resize', resizeKawarp);

  // Timeline & Scrubber Progress
  playerManager.addEventListener(cast.framework.events.EventType.TIME_UPDATE, () => {
    try {
      const current = playerManager.getCurrentTimeSec();
      const duration = playerManager.getDurationSec() || currentMediaState.duration;

      if (duration && duration > 0) {
        const pct = Math.min(100, Math.max(0, (current / duration) * 100));
        progressFill.style.width = `${pct}%`;
        currentTimeLabel.textContent = formatTime(current);
        totalTimeLabel.textContent = formatTime(duration);
      } else {
        currentTimeLabel.textContent = formatTime(current);
      }
    } catch (e) {
      console.warn('Error on TIME_UPDATE:', e);
    }
  });

  // Media Loaded
  playerManager.addEventListener(cast.framework.events.EventType.MEDIA_STATUS, (event) => {
    const media = event.mediaStatus?.media || playerManager.getMediaInformation();
    if (media) {
      const contentId = media.contentId || media.contentUrl || '';
      const title = media.metadata?.title || media.customData?.title || 'Unknown Title';
      if (contentId !== currentMediaState.contentId || title !== currentMediaState.title) {
        applyMediaData(media);
      }
      currentMediaState.duration = media.duration || 0;
      totalTimeLabel.textContent = formatTime(media.duration);
    }
    const state = event.mediaStatus?.playerState || playerManager.getPlayerState?.();
    if (state === 'PLAYING' || state === 'PAUSED' || state === 'BUFFERING') {
      setPlaybackState(state);
    }
  });

  // Playback State Transitions
  playerManager.addEventListener(cast.framework.events.EventType.PLAYING, () => {
    setPlaybackState('PLAYING');
  });

  playerManager.addEventListener(cast.framework.events.EventType.PAUSE, () => {
    setPlaybackState('PAUSED');
  });

  playerManager.addEventListener(cast.framework.events.EventType.BUFFERING, (event) => {
    if (event.isBuffering) {
      setPlaybackState('BUFFERING');
    } else {
      // CAF emits this event again when buffering ends. It need not emit
      // another PLAYING event for every queue item or recovery.
      const state = playerManager.getPlayerState?.();
      setPlaybackState(state === 'PAUSED' ? 'PAUSED' : 'PLAYING');
    }
  });

  // When playback ends
  playerManager.addEventListener(cast.framework.events.EventType.ENDED, () => {
    setPlaybackState('ENDED');
    // Check if there are more queue items, otherwise return to idle after brief pause
    setTimeout(() => {
      const queue = playerManager.getQueueManager ? playerManager.getQueueManager().getQueueItems() : [];
      if (!queue || queue.length === 0) {
        showScreen('idle');
      }
    }, 1500);
  });

  // Start Cast Context
  const playbackConfig = new cast.framework.PlaybackConfig();
  // Enable smooth buffering
  playbackConfig.autoResumeDuration = 5;

  const options = new cast.framework.CastReceiverOptions();
  options.playbackConfig = playbackConfig;
  options.disableIdleTimeout = false;

  context.start(options);
  console.log('Orchard Custom Cast Web Receiver initialized successfully.');
})();
