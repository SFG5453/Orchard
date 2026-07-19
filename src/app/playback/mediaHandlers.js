import { nextTick } from 'vue';
import { reliablePlaybackDuration } from './playbackDuration.js';
import { installPlaybackRecoveryActions } from './playbackRecoveryActions.js';

export function installMediaHandlers(ctx) {
  installPlaybackRecoveryActions(ctx);
  ctx.onNowArtworkVideoError = function onNowArtworkVideoError() {
    ctx.nowArtworkVideoFailed.value = true;
  };

  ctx.onDetailArtworkVideoError = function onDetailArtworkVideoError() {
    ctx.detailArtworkVideoFailed.value = true;
  };

  ctx.playArtworkVideo = function playArtworkVideo(videoRef, failedRef) {
    nextTick(() => {
      const video = videoRef.value;
      if (!video) return;

      video.loop = true;
      video.muted = true;
      video.playsInline = true;
      const playback = video.play();
      if (playback?.catch) playback.catch(() => {
        failedRef.value = true;
      });
    });
  };

  ctx.restartArtworkVideo = function restartArtworkVideo(videoRef, failedRef) {
    const video = videoRef.value;
    if (!video || failedRef.value) return;

    try {
      video.currentTime = 0;
    } catch {
      // Some remote media objects reject seeks while changing ready state.
    }

    ctx.playArtworkVideo(videoRef, failedRef);
  };

  ctx.keepArtworkVideoPlaying = function keepArtworkVideoPlaying(videoRef, failedRef) {
    const video = videoRef.value;
    if (!video || failedRef.value || video.ended) return;

    window.setTimeout(() => {
      if (videoRef.value?.paused) ctx.playArtworkVideo(videoRef, failedRef);
    }, 80);
  };

  ctx.playNowArtworkVideo = function playNowArtworkVideo() {
    if (!ctx.isPlaying.value) return;
    ctx.playArtworkVideo(ctx.nowArtworkVideoRef, ctx.nowArtworkVideoFailed);
  };

  ctx.playRightPanelArtworkVideo = function playRightPanelArtworkVideo() {
    if (!ctx.isPlaying.value) return;
    ctx.playArtworkVideo(ctx.rightPanelArtworkVideoRef, ctx.nowArtworkVideoFailed);
  };

  ctx.syncNowArtworkVideoPlayback = function syncNowArtworkVideoPlayback() {
    const artworkVideoRefs = [ctx.nowArtworkVideoRef, ctx.rightPanelArtworkVideoRef];

    for (const videoRef of artworkVideoRefs) {
      const video = videoRef.value;
      if (!video) continue;

      if (ctx.isPlaying.value) {
        ctx.playArtworkVideo(videoRef, ctx.nowArtworkVideoFailed);
      } else {
        video.pause();
      }
    }
  };

  ctx.playDetailArtworkVideo = function playDetailArtworkVideo() {
    ctx.playArtworkVideo(ctx.detailArtworkVideoRef, ctx.detailArtworkVideoFailed);
  };

  ctx.playInlineArtworkVideo = function playInlineArtworkVideo(event) {
    const video = event?.target;
    if (!video) return;

    video.loop = true;
    video.muted = true;
    video.playsInline = true;
    const playback = video.play();
    if (playback?.catch) playback.catch(() => {});
  };

  ctx.restartNowArtworkVideo = function restartNowArtworkVideo() {
    if (!ctx.isPlaying.value) return;
    ctx.restartArtworkVideo(ctx.nowArtworkVideoRef, ctx.nowArtworkVideoFailed);
  };

  ctx.restartRightPanelArtworkVideo = function restartRightPanelArtworkVideo() {
    if (!ctx.isPlaying.value) return;
    ctx.restartArtworkVideo(ctx.rightPanelArtworkVideoRef, ctx.nowArtworkVideoFailed);
  };

  ctx.restartDetailArtworkVideo = function restartDetailArtworkVideo() {
    ctx.restartArtworkVideo(ctx.detailArtworkVideoRef, ctx.detailArtworkVideoFailed);
  };

  ctx.keepNowArtworkVideoPlaying = function keepNowArtworkVideoPlaying() {
    if (!ctx.isPlaying.value) return;
    ctx.keepArtworkVideoPlaying(ctx.nowArtworkVideoRef, ctx.nowArtworkVideoFailed);
  };

  ctx.keepRightPanelArtworkVideoPlaying = function keepRightPanelArtworkVideoPlaying() {
    if (!ctx.isPlaying.value) return;
    ctx.keepArtworkVideoPlaying(ctx.rightPanelArtworkVideoRef, ctx.nowArtworkVideoFailed);
  };

  ctx.keepDetailArtworkVideoPlaying = function keepDetailArtworkVideoPlaying() {
    ctx.keepArtworkVideoPlaying(ctx.detailArtworkVideoRef, ctx.detailArtworkVideoFailed);
  };

  ctx.onAudioTime = function onAudioTime(event) {
    if (!ctx.isCurrentAudioEvent(event) || ctx.isSeeking.value) return;
    const media = ctx.currentPlaybackElement();
    const playbackTime = media?.currentTime || 0;
    if (!ctx.buffering.value || playbackTime > ctx.currentTime.value + 0.25) {
      ctx.clearPlaybackStallRecovery();
    }
    ctx.currentTime.value = playbackTime;
    ctx.seekPosition.value = ctx.currentTime.value;
    ctx.reportYouTubeHistoryProgress?.();
    ctx.reportLastfmProgress?.();
    ctx.syncVideoCompanionAudio(ctx.currentTime.value);
    if (!ctx.activeTrackIsVideo.value) void ctx.maybeStartAutoCrossfade();
  };

  ctx.onAudioLoaded = function onAudioLoaded(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    ctx.duration.value = reliablePlaybackDuration(ctx, ctx.currentPlaybackElement());
    ctx.seekPosition.value = ctx.currentTime.value;
    ctx.buffering.value = false;
    ctx.clearPlaybackStallRecovery();
  };

  ctx.onAudioWaiting = function onAudioWaiting(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    ctx.buffering.value = true;
    ctx.schedulePlaybackStallRecovery(event);
  };

  ctx.onAudioPlaying = function onAudioPlaying(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    ctx.buffering.value = false;
    ctx.clearPlaybackStallRecovery();
    ctx.isPlaying.value = true;
    ctx.startYouTubeHistory?.(ctx.activeTrack.value?.youtubeVideoId || ctx.activeTrack.value?.id);
    ctx.startLastfmTrack?.();
  };

  ctx.onAudioCanPlay = function onAudioCanPlay(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    ctx.buffering.value = false;
    ctx.clearPlaybackStallRecovery();
  };

  ctx.onAudioPlay = function onAudioPlay(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    ctx.isPlaying.value = true;
    if (ctx.activeTrackIsVideo.value && ctx.videoAudioRef.value?.src && ctx.videoAudioRef.value.paused) {
      ctx.syncVideoCompanionAudio();
      ctx.videoAudioRef.value.play().catch((error) => {
        if (ctx.isInterruptedPlaybackRequest(error)) return;
        ctx.playbackError.value = error.message;
      });
    }
  };

  ctx.onAudioPause = function onAudioPause(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    ctx.clearPlaybackStallRecovery();
    ctx.reportYouTubeHistoryProgress?.({ force: true });
    ctx.buffering.value = false;
    ctx.isPlaying.value = false;
    if (ctx.activeTrackIsVideo.value) ctx.videoAudioRef.value?.pause();
  };

  ctx.onAudioEnded = function onAudioEnded(event) {
    if (!ctx.isCurrentAudioEvent(event)) return;
    if (ctx.autoCrossfade.isActive()) return;
    if (!ctx.activeTrackIsVideo.value && ctx.recoverPrematureAudioEnd(event.target)) return;
    ctx.clearPlaybackStallRecovery();
    ctx.finishYouTubeHistory?.();
    ctx.reportLastfmProgress?.();
    if (ctx.activeTrackIsVideo.value) ctx.videoAudioRef.value?.pause();
    if (ctx.completeSleepTimerAfterTrack()) return;
    void ctx.finishAudioTrack();
  };
  ctx.useBrowserMediaSession = function useBrowserMediaSession() {
    return Boolean('mediaSession' in navigator && !window.orchardSystemMedia?.nativeSystemMedia);
  };

  ctx.applyMediaSessionMetadata = function applyMediaSessionMetadata() {
    if (!ctx.useBrowserMediaSession()) return;

    if (!ctx.activeTrack.value) {
      navigator.mediaSession.metadata = null;
      return;
    }

    const artist = ctx.activeArtist.value || 'Orchard';
    const artworkUrl = ctx.nowArtworkImage.value || ctx.activeTrack.value.thumbnail;
    const artwork = artworkUrl
      ? [
        { src: artworkUrl, sizes: '96x96', type: 'image/jpeg' },
        { src: artworkUrl, sizes: '128x128', type: 'image/jpeg' },
        { src: artworkUrl, sizes: '192x192', type: 'image/jpeg' },
        { src: artworkUrl, sizes: '256x256', type: 'image/jpeg' },
        { src: artworkUrl, sizes: '384x384', type: 'image/jpeg' },
        { src: artworkUrl, sizes: '512x512', type: 'image/jpeg' }
      ]
      : [];

    navigator.mediaSession.metadata = new MediaMetadata({
      title: ctx.activeTrack.value.title || 'Orchard',
      artist,
      album: ctx.activeTrack.value.album || ctx.activeTrack.value.subtitle || 'YouTube Music',
      artwork
    });
  };

  ctx.updateMediaSessionPlaybackState = function updateMediaSessionPlaybackState() {
    if (!ctx.useBrowserMediaSession()) return;

    navigator.mediaSession.playbackState = ctx.activeTrack.value
      ? (ctx.isPlaying.value ? 'playing' : 'paused')
      : 'none';
  };

  ctx.updateMediaSessionPositionState = function updateMediaSessionPositionState() {
    if (!ctx.useBrowserMediaSession() || ctx.activeTrackIsLive.value || !ctx.activeTrack.value || !Number.isFinite(ctx.duration.value) || ctx.duration.value <= 0) {
      return;
    }

    if (typeof navigator.mediaSession.setPositionState !== 'function') return;

    navigator.mediaSession.setPositionState({
      duration: ctx.duration.value,
      playbackRate: ctx.currentPlaybackElement()?.playbackRate || 1,
      position: Math.max(0, Math.min(ctx.currentTime.value, ctx.duration.value))
    });
  };

  ctx.clearMediaSessionPositionState = function clearMediaSessionPositionState() {
    if (!ctx.useBrowserMediaSession()) return;
    if (typeof navigator.mediaSession.setPositionState !== 'function') return;

    try {
      navigator.mediaSession.setPositionState();
    } catch {
      // Chromium may reject empty position resets on some builds.
    }
  };

  ctx.discordPresencePayload = function discordPresencePayload() {
    if (!ctx.activeTrack.value || ctx.playbackError.value) return null;

    const artist = ctx.activeArtist.value || ctx.activeTrack.value.artist || ctx.activeTrack.value.artists?.[0] || '';

    return {
      title: ctx.activeTrack.value.title || 'Playing music',
      artist,
      album: ctx.activeTrack.value.album || '',
      activityName: ctx.discordRpcActivityName.value,
      youtubeVideoId: ctx.activeTrack.value.id || '',
      thumbnailUrl: ctx.nowArtworkImage.value || ctx.activeTrack.value.thumbnail || '',
      artworkUrl: ctx.discordArtworkImage.value,
      animatedArtworkUrl: ctx.nowArtworkVideo.value,
      isPlaying: Boolean(ctx.isPlaying.value && !ctx.buffering.value),
      currentTime: Math.max(0, ctx.displayedTime.value || 0),
      duration: ctx.duration.value || ctx.activeTrack.value.durationSeconds || 0
    };
  };

  ctx.syncDiscordPresence = function syncDiscordPresence() {
    const discord = window.orchardDiscord;
    if (!discord) return;

    if (!ctx.discordRpcEnabled.value) {
      discord.clearPresence()?.catch?.(() => {});
      return;
    }

    const presence = ctx.discordPresencePayload();
    const request = presence ? discord.setPresence(presence) : discord.clearPresence();
    request?.catch?.(() => {});
  };

  ctx.queueDiscordPresenceSync = function queueDiscordPresenceSync() {
    window.clearTimeout(ctx.discordPresenceSyncTimer);

    if (!ctx.discordRpcEnabled.value) {
      ctx.syncDiscordPresence();
      return;
    }

    ctx.discordPresenceSyncTimer = window.setTimeout(ctx.syncDiscordPresence, 180);
  };

  ctx.registerMediaSessionHandlers = function registerMediaSessionHandlers() {
    if (!ctx.useBrowserMediaSession()) return;

    const actions = {
      play: () => {
        if (ctx.currentPlaybackElement()?.src) ctx.togglePlayback();
      },
      pause: () => {
        if (!ctx.currentPlaybackElement()?.paused) ctx.togglePlayback();
      },
      previoustrack: () => {
        if (ctx.activeTrack.value && !ctx.buffering.value) ctx.playPrevious();
      },
      nexttrack: () => {
        if ((ctx.queue.value.length || ctx.repeatMode.value !== 'off') && !ctx.buffering.value) {
          ctx.playNext({ skipRepeatOne: true });
        }
      },
      seekbackward: (details = {}) => {
        ctx.seekRelative(-(details.seekOffset || 10));
      },
      seekforward: (details = {}) => {
        ctx.seekRelative(details.seekOffset || 10);
      },
      seekto: (details = {}) => {
        if (typeof details.seekTime === 'number') ctx.seek(details.seekTime);
      },
      stop: () => {
        const media = ctx.currentPlaybackElement();
        if (!media) return;
        media.pause();
        if (ctx.activeTrackIsVideo.value) ctx.videoAudioRef.value?.pause();
        media.currentTime = 0;
        ctx.syncVideoCompanionAudio(0);
        ctx.currentTime.value = 0;
        ctx.seekPosition.value = 0;
        ctx.isPlaying.value = false;
      }
    };

    for (const [action, handler] of Object.entries(actions)) {
      try {
        navigator.mediaSession.setActionHandler(action, handler);
      } catch {
        // Unsupported actions vary by platform.
      }
    }
  };

  ctx.isEditableKeyboardTarget = function isEditableKeyboardTarget(target) {
    if (!(target instanceof Element)) return false;
    const tagName = target.tagName.toLowerCase();
    return target.isContentEditable ||
      tagName === 'input' ||
      tagName === 'textarea' ||
      tagName === 'select' ||
      Boolean(target.closest('[contenteditable="true"], input, textarea, select'));
  };
  ctx.onGlobalKeydown = function onGlobalKeydown(event) {
    if (ctx.handleSpotlightShortcut?.(event) || ctx.spotlightOpen?.value) return;
    if (ctx.handleCollectionQuickSearchShortcut?.(event) || ctx.collectionQuickSearchOpen?.value) return;
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.repeat) return;
    if (ctx.isEditableKeyboardTarget(event.target)) return;

    if (event.code === 'Space') {
      if (!ctx.currentPlaybackElement()?.src) return;
      event.preventDefault();
      ctx.togglePlayback();
      return;
    }

    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      ctx.seekRelative(-5);
      return;
    }

    if (event.key === 'ArrowRight') {
      event.preventDefault();
      ctx.seekRelative(5);
    }
  };

  ctx.clearMediaSessionHandlers = function clearMediaSessionHandlers() {
    if (!ctx.useBrowserMediaSession()) return;

    for (const action of ['play', 'pause', 'previoustrack', 'nexttrack', 'seekbackward', 'seekforward', 'seekto', 'stop']) {
      try {
        navigator.mediaSession.setActionHandler(action, null);
      } catch {
        // Ignore unsupported actions during teardown.
      }
    }
  };

}
