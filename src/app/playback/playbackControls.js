import { nextTick } from 'vue';
import { playlistPreviousState } from './playbackCollectionQueue.js';
import { reliablePlaybackDuration } from './playbackDuration.js';
import { createSmartCrossfadeMixPresentation } from './smartCrossfadeMixPresentation.js';

const LYRIC_AUTO_SCROLL_RESUME_DELAY_MS = 1800;

function isUnavailableTrackError(error) {
  return /\b(?:unavailable|not available|no playable (?:audio |video )?format|video unavailable|private video|removed by uploader)\b/i
    .test(String(error?.message || error || ''));
}

export function playbackNeedsFreshStream(media, playbackError = '') {
  return Boolean(
    playbackError || media?.ended || media?.error || media?.networkState === 3
  );
}

export function installPlaybackControls(ctx) {
  let crossfadeClockTimer = 0;
  let fullscreenPlayerDomActive = false;

  ctx.dismissSmartCrossfadeMix = function dismissSmartCrossfadeMix() {
    window.clearTimeout(ctx.smartCrossfadeMixTimer);
    ctx.smartCrossfadeMixTimer = 0;
    if (!ctx.smartCrossfadeMix.value.visible) return;
    ctx.smartCrossfadeMix.value = { ...ctx.smartCrossfadeMix.value, visible: false };
  };

  ctx.showSmartCrossfadeMix = function showSmartCrossfadeMix(details) {
    window.clearTimeout(ctx.smartCrossfadeMixTimer);
    const presentation = createSmartCrossfadeMixPresentation({
      id: ++ctx.smartCrossfadeMixSequence,
      currentArtwork: ctx.nowArtworkImage.value,
      ...details
    });
    ctx.smartCrossfadeMix.value = presentation;
    const timerMs = ctx.fullscreenPlayerOpen.value
      ? presentation.durationMs
      : presentation.fadeDurationMs;
    ctx.smartCrossfadeMixTimer = window.setTimeout(
      ctx.dismissSmartCrossfadeMix,
      timerMs
    );
  };

  ctx.cancelActiveCrossfade = function cancelActiveCrossfade() {
    const wasActive = Boolean(ctx.autoCrossfade?.isActive?.());
    ctx.autoCrossfade?.cancel?.();
    if (ctx.smartCrossfadeMix?.value?.visible) ctx.dismissSmartCrossfadeMix();
    return wasActive;
  };

  ctx.recoverPrematureAudioEnd = function recoverPrematureAudioEnd(media) {
    const track = ctx.activeTrack.value;
    const expected = reliablePlaybackDuration(ctx, media, track);
    const stoppedAt = Number(media?.currentTime) || ctx.currentTime.value || 0;
    const remaining = expected - stoppedAt;
    if (!track?.id || expected < 45 || remaining <= Math.max(12, expected * 0.08)) return false;

    ctx.autoCrossfade.cancel();
    if (!track.streamRefreshTried) {
      return Boolean(ctx.retryAudioStream(track, { refreshStream: true, resumeAt: stoppedAt }));
    }
    if (!track.playbackFallbackTried) {
      return Boolean(ctx.retryAudioWithAlternateFormat(track, { resumeAt: stoppedAt }));
    }
    ctx.isPlaying.value = false;
    ctx.playbackError.value = `Playback stopped early at ${Math.round(stoppedAt)} seconds.`;
    return true;
  };

  ctx.syncAudioPlaybackClock = function syncAudioPlaybackClock() {
    const media = ctx.currentPlaybackElement();
    if (!media || ctx.isSeeking.value || ctx.activeTrackIsVideo.value) return;

    const playbackTime = Number(media.currentTime);
    if (Number.isFinite(playbackTime)) {
      ctx.currentTime.value = playbackTime;
      ctx.seekPosition.value = playbackTime;
    }

    const mediaDuration = reliablePlaybackDuration(ctx, media);
    if (mediaDuration) ctx.duration.value = mediaDuration;
    if (!media.paused && !media.ended) void ctx.maybeStartAutoCrossfade();
  };

  ctx.startCrossfadeClock = function startCrossfadeClock() {
    if (crossfadeClockTimer || !ctx.crossfadeEnabled.value || ctx.activeTrackIsVideo.value) return;
    crossfadeClockTimer = window.setInterval(ctx.syncAudioPlaybackClock, 120);
  };

  ctx.stopCrossfadeClock = function stopCrossfadeClock() {
    window.clearInterval(crossfadeClockTimer);
    crossfadeClockTimer = 0;
  };

  async function waitForFullscreenPlayer() {
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await nextTick();
      if (ctx.fullscreenPlayerRef.value) return ctx.fullscreenPlayerRef.value;
      await new Promise((resolve) => window.requestAnimationFrame(resolve));
    }

    return ctx.fullscreenPlayerRef.value;
  }

  ctx.openFullscreenPlayer = async function openFullscreenPlayer() {
    if (!ctx.activeTrack.value || ctx.fullscreenPlayerOpen.value) return;

    fullscreenPlayerDomActive = false;
    ctx.fullscreenPlayerOpen.value = true;
    const player = await waitForFullscreenPlayer();

    try {
      await window.orchardWindow?.setFullscreen(true);
    } catch {
      // Browser fullscreen remains available outside Electron or if the shell rejects the request.
    }

    if (!player?.requestFullscreen || document.fullscreenElement) return;

    try {
      await player.requestFullscreen();
      fullscreenPlayerDomActive = true;
    } catch {
      // The in-window overlay is still a complete player when fullscreen is unavailable.
    }
  };

  ctx.closeFullscreenPlayer = async function closeFullscreenPlayer() {
    fullscreenPlayerDomActive = false;
    ctx.fullscreenPlayerOpen.value = false;

    if (document.fullscreenElement && document.exitFullscreen) {
      try {
        await document.exitFullscreen();
      } catch {
        // The overlay is already closed; the native window can still leave fullscreen.
      }
    }

    try {
      await window.orchardWindow?.setFullscreen(false);
    } catch {
      // Browser-only builds have no native window fullscreen state to restore.
    }
  };

  ctx.onFullscreenPlayerChange = function onFullscreenPlayerChange() {
    if (document.fullscreenElement === ctx.fullscreenPlayerRef.value) {
      fullscreenPlayerDomActive = true;
      return;
    }

    if (!document.fullscreenElement && fullscreenPlayerDomActive && ctx.fullscreenPlayerOpen.value) {
      void ctx.closeFullscreenPlayer();
    }
  };

  ctx.togglePlayback = function togglePlayback() {
    if (ctx.listeningParty.value?.status === 'connected' && !ctx.listeningPartyIsHost.value) {
      ctx.sendListeningPartyRequest({ action: ctx.isPlaying.value ? 'pause' : 'play' });
      return;
    }
    ctx.cancelActiveCrossfade();
    const media = ctx.currentPlaybackElement();
    if (ctx.activeTrack.value && (!media?.src || playbackNeedsFreshStream(media, ctx.playbackError.value))) {
      ctx.playTrack(ctx.activeTrack.value, {
        mediaKind: ctx.activeMediaKind.value,
        queueSource: [ctx.activeTrack.value, ...ctx.queue.value],
        queueAlreadyShuffled: Boolean(ctx.shuffleEnabled?.value),
        refreshStream: true,
        skipHistory: true
      });
      return;
    }
    if (!media?.src) return;
    const videoAudio = ctx.activeTrackIsVideo.value ? ctx.videoAudioRef.value : null;

    if (media.paused) {
      ctx.playbackError.value = '';
      ctx.audioAnalyzer.resume().catch(() => {});
      ctx.syncVideoCompanionAudio();
      const playRequest = videoAudio?.src
        ? Promise.all([media.play(), videoAudio.play()])
        : media.play();
      playRequest.catch((error) => {
        if (ctx.isInterruptedPlaybackRequest(error)) return;
        ctx.playbackError.value = error.message;
      });
    } else {
      media.pause();
      videoAudio?.pause();
    }
  };

  ctx.seekRelative = function seekRelative(offsetSeconds) {
    ctx.seek((ctx.currentPlaybackElement()?.currentTime || ctx.currentTime.value || 0) + offsetSeconds);
  };

  ctx.playHistoryTrack = function playHistoryTrack(track) {
    if (!track?.id) return;
    ctx.playTrack(track, { queueSource: [track] });
  };

  ctx.minimizeVideoPlayer = function minimizeVideoPlayer() {
    ctx.videoPlayerMinimized.value = true;
  };

  ctx.expandVideoPlayer = function expandVideoPlayer() {
    ctx.videoPlayerMinimized.value = false;
  };

  ctx.repeatQueueSource = function repeatQueueSource() {
    const seen = new Set();
    const ordered = [
      ...ctx.history.value.slice().reverse(),
      ctx.activeTrack.value
    ].filter(ctx.isPlayableTrack);

    return ordered.filter((track) => {
      if (seen.has(track.id)) return false;
      seen.add(track.id);
      return true;
    });
  };

  ctx.playNext = async function playNext(options = {}) {
    if (!options.fromListeningPartyRequest && !options.fromEnded && ctx.requestListeningPartyHostControl?.({ action: 'next' })) return;
    ctx.cancelActiveCrossfade();
    if (ctx.repeatMode.value === 'one' && ctx.activeTrack.value && !options.skipRepeatOne) {
      await ctx.playTrack(ctx.activeTrack.value, {
        mediaKind: ctx.activeMediaKind.value,
        queueSource: [ctx.activeTrack.value, ...ctx.queue.value],
        queueAlreadyShuffled: Boolean(ctx.shuffleEnabled?.value),
        refreshStream: true,
        skipHistory: true
      });
      return;
    }

    let [next, ...remainingQueue] = ctx.queue.value;
    if (!next && ctx.repeatMode.value === 'queue' && ctx.activeTrack.value) {
      const repeatQueue = ctx.repeatQueueSource();
      [next, ...remainingQueue] = (ctx.shuffleEnabled.value ? ctx.shuffleItems(repeatQueue) : repeatQueue);
    }
    if (!next && ctx.autoplayEnabled.value) {
      await ctx.ensureAutoplayQueue({ force: true });
      [next, ...remainingQueue] = ctx.queue.value;
    }
    if (!next) {
      ctx.clearNextPreload();
      return;
    }

    while (next) {
      let resolved = ctx.preloadedTrackMatches(next) ? ctx.nextTrackPreload.value?.resolved : null;
      if (!resolved) {
        try {
          resolved = await ctx.resolvePlayableTrack(next);
        } catch (error) {
          if (!isUnavailableTrackError(error)) {
            ctx.playbackError.value = error.message;
            return;
          }
          ctx.removeUnavailableQueueTrack?.(next);
          [next, ...remainingQueue] = ctx.queue.value;
          continue;
        }
      }

      ctx.playTrack(next, {
        queueSource: [next, ...remainingQueue],
        queueAlreadyShuffled: ctx.shuffleEnabled.value,
        resolved,
        sessionAction: options.fromEnded ? 'ended' : 'manual'
      });
      return;
    }
    ctx.clearNextPreload();
  };

  ctx.maybeStartAutoCrossfade = async function maybeStartAutoCrossfade(options = {}) {
    if (!ctx.crossfadeEnabled.value) return false;
    if (ctx.sleepTimerMode.value === 'end-track' || ctx.sleepTimerVolumeFactor.value < 1) {
      return false;
    }
    if (ctx.repeatMode.value === 'one') return false;
    if (ctx.activeTrackIsVideo.value) return false;

    const next = ctx.queue.value[0];
    const fromAudio = ctx.currentAudio();
    const toAudio = ctx.standbyAudio();

    if (!next?.id || !fromAudio || !toAudio) {
      return false;
    }
    const mediaCurrentTime = Number(fromAudio.currentTime);
    const mediaDuration = reliablePlaybackDuration(ctx, fromAudio);
    const forceFadeSeconds = options.reason === 'ended-handoff'
      ? 0.05
      : Math.min(1, ctx.crossfadeSeconds.value || 1);
    const transition = options.force
      ? { shouldStart: true, fadeSeconds: forceFadeSeconds, reason: options.reason || 'forced-handoff' }
      : ctx.autoCrossfade.transitionPlan({
        currentAudio: fromAudio,
        currentTime: Number.isFinite(mediaCurrentTime) ? mediaCurrentTime : ctx.currentTime.value,
        currentTrack: ctx.activeTrack.value,
        duration: mediaDuration,
        nextTrack: next,
        analysis: ctx.crossfadeAnalysis.value,
        nextAnalysis: ctx.nextCrossfadeAnalysis.value
      });
    if (!transition.shouldStart || (!options.force && !ctx.isPlaying.value) || ctx.isSeeking.value || ctx.autoCrossfade.isActive()) {
      return false;
    }

    if (!ctx.preloadedTrackMatches(next) || !toAudio.src) {
      const didPreload = await ctx.preloadNextTrack({ force: true });
      if (!didPreload || !ctx.preloadedTrackMatches(next) || !toAudio.src) {
        return false;
      }
    }

    const resolved = ctx.nextTrackPreload.value?.resolved;
    if (!resolved) {
      return false;
    }

    const previousTrack = ctx.activeTrack.value;
    const nextTrack = ctx.activeTrackFromResolved(next, resolved);
    const nextQueue = ctx.queue.value.slice(1);
    const nextDeck = ctx.activeAudioDeck.value === 'main' ? 'next' : 'main';
    const showSmartMix = ctx.crossfadeMode.value === 'smart' &&
      !options.force &&
      transition.transitionStyle !== 'gapless';

    if (showSmartMix) {
      ctx.showSmartCrossfadeMix({
        fromTrack: previousTrack,
        toTrack: nextTrack,
        transition,
        analysis: ctx.crossfadeAnalysis.value,
        nextAnalysis: ctx.nextCrossfadeAnalysis.value
      });
    }

    const didCrossfade = await ctx.autoCrossfade.start({
      fromAudio,
      toAudio,
      transition,
      volume: ctx.volume.value,
      onPromote: () => {
        ctx.finishYouTubeHistory?.();
        ctx.markPlaylistTrackPlayed?.(previousTrack);
        if (previousTrack?.id) {
          ctx.history.value.unshift(previousTrack);
          ctx.history.value = ctx.history.value.slice(0, 30);
        }

        ctx.nextPreloadRequest += 1;
        ctx.nextTrackPreload.value = null;
        ctx.activeAudioDeck.value = nextDeck;
        ctx.activeTrack.value = nextTrack;
        ctx.startYouTubeHistory?.(nextTrack.youtubeVideoId || nextTrack.id);
        ctx.promoteCrossfadeAnalysis(nextTrack.id);
        if (ctx.crossfadeAnalysis.value.status !== 'ready') {
          void ctx.analyzeCurrentCrossfadeTrack(nextTrack, resolved.streamUrl, nextTrack.durationSeconds || 0);
        }
        ctx.queue.value = nextQueue;
        if (ctx.shuffleEnabled.value && ctx.shuffleSourceQueue.value.length) {
          ctx.shuffleSourceQueue.value = ctx.shuffleSourceQueue.value.filter((track) => track.id !== nextTrack.id);
        }
        void ctx.refillPlaylistQueue?.();
        ctx.currentTime.value = toAudio.currentTime || 0;
        ctx.seekPosition.value = ctx.currentTime.value;
        ctx.duration.value = reliablePlaybackDuration(ctx, toAudio, nextTrack);
        ctx.buffering.value = false;
        ctx.isPlaying.value = true;
        ctx.recordSessionEvent?.('crossfade', nextTrack, {
          fromTrack: previousTrack,
          queue: nextQueue,
          transitionMode: ctx.crossfadeMode.value,
          transitionReason: transition.reason || '',
          transitionStyle: transition.transitionStyle || '',
          progressSeconds: ctx.currentTime.value,
          durationSeconds: ctx.duration.value
        });
      },
      onComplete: () => {
        ctx.clearAudioElement(fromAudio);
        void ctx.preloadNextTrack();
      },
      onError: (error) => {
        if (showSmartMix) ctx.dismissSmartCrossfadeMix();
        ctx.playbackError.value = error.message;
      }
    });

    if (!didCrossfade && showSmartMix) ctx.dismissSmartCrossfadeMix();
    return didCrossfade;
  };

  ctx.finishAudioTrack = async function finishAudioTrack() {
    if (!ctx.activeTrackIsVideo.value) {
      const didHandoff = await ctx.maybeStartAutoCrossfade({ force: true, reason: 'ended-handoff' });
      if (didHandoff) return;
    }

    ctx.playNext({ fromEnded: true });
  };

  ctx.playPrevious = function playPrevious(options = {}) {
    if (!options.fromListeningPartyRequest && ctx.requestListeningPartyHostControl?.({ action: 'previous' })) return;
    ctx.cancelActiveCrossfade();
    const playlistContext = ctx.playbackPlaylistContext.value;
    if (playlistContext && !ctx.shuffleEnabled.value && !playlistContext.shuffled) {
      const { activeIndex, previousTrack } = playlistPreviousState(playlistContext.allTracks, ctx.activeTrack.value?.id);
      if (activeIndex === 0) {
        ctx.seek(0);
        return;
      }
      if (previousTrack) {
        if (ctx.history.value[0]?.id === previousTrack.id) ctx.history.value.shift();
        if (ctx.activeTrack.value) {
          ctx.queue.value = [ctx.activeTrack.value, ...ctx.queue.value.filter((track) => track.id !== ctx.activeTrack.value.id)];
        }
        ctx.playTrack(previousTrack, { skipHistory: true, preserveQueue: true, sessionAction: 'previous' });
        return;
      }
    }
    const previous = ctx.history.value.shift();
    if (!previous) {
      if (ctx.activeTrack.value) ctx.seek(0);
      return;
    }

    if (ctx.activeTrack.value) ctx.queue.value.unshift(ctx.activeTrack.value);
    ctx.playTrack(previous, { skipHistory: true, preserveQueue: true, sessionAction: 'previous' });
  };

  ctx.toggleShuffle = function toggleShuffle(options = {}) {
    if (!options.fromListeningPartyRequest && ctx.requestListeningPartyHostControl?.({ action: 'toggle-shuffle' })) return;
    if (ctx.shuffleEnabled.value) {
      ctx.shuffleEnabled.value = false;
      if (ctx.shuffleSourceQueue.value.length) {
        ctx.queue.value = ctx.shuffleSourceQueue.value.filter(ctx.isPlayableTrack);
      }
      ctx.shuffleSourceQueue.value = [];
    } else {
      ctx.shuffleSourceQueue.value = ctx.queue.value.filter(ctx.isPlayableTrack);
      ctx.queue.value = ctx.shuffleItems(ctx.shuffleSourceQueue.value);
      ctx.shuffleEnabled.value = true;
    }

    ctx.clearNextPreload();
  };

  ctx.cycleRepeatMode = function cycleRepeatMode(options = {}) {
    if (!options.fromListeningPartyRequest && ctx.requestListeningPartyHostControl?.({ action: 'cycle-repeat' })) return;
    const order = ['off', 'queue', 'one'];
    const nextIndex = (order.indexOf(ctx.repeatMode.value) + 1) % order.length;
    ctx.repeatMode.value = order[nextIndex] || 'off';
  };

  ctx.repeatModeTitle = function repeatModeTitle() {
    if (ctx.repeatMode.value === 'one') return 'Repeat one';
    if (ctx.repeatMode.value === 'queue') return 'Repeat queue';
    return 'Repeat off';
  };

  ctx.seek = function seek(value) {
    if (ctx.listeningParty.value?.status === 'connected' && !ctx.listeningPartyIsHost.value && !ctx.applyingListeningPartyState) {
      ctx.sendListeningPartyRequest({ action: 'seek', currentTime: Number(value) || 0 });
      return;
    }
    ctx.cancelActiveCrossfade();
    const media = ctx.currentPlaybackElement();
    if (!media || !ctx.duration.value || ctx.activeTrackIsLive.value) return;
    const target = Math.max(0, Math.min(Number(value) || 0, ctx.duration.value));
    ctx.currentTime.value = target;
    ctx.seekPosition.value = target;

    if (typeof media.fastSeek === 'function') media.fastSeek(target);
    else media.currentTime = target;
    ctx.syncVideoCompanionAudio(target);

    ctx.queueDiscordPresenceSync();
  };

  ctx.seekToLyric = function seekToLyric(item) {
    if (!item?.canSeek) return;
    ctx.seek(item.seekTime);
  };

  ctx.lyricAutoScrollPaused = function lyricAutoScrollPaused() {
    return Date.now() < ctx.lyricAutoScrollPausedUntil;
  };

  ctx.pauseLyricAutoScroll = function pauseLyricAutoScroll(duration = LYRIC_AUTO_SCROLL_RESUME_DELAY_MS) {
    ctx.lyricAutoScrollPausedUntil = Date.now() + duration;
    window.clearTimeout(ctx.lyricAutoScrollPauseTimer);
    ctx.lyricAutoScrollPauseTimer = window.setTimeout(() => {
      ctx.lyricAutoScrollPauseTimer = 0;
      ctx.lyricAutoScrollPausedUntil = 0;
      if (ctx.activeLyricKey.value) void ctx.scrollActiveLyric({ force: true });
    }, duration);
  };

  ctx.onLyricsUserScrollStart = function onLyricsUserScrollStart() {
    ctx.pauseLyricAutoScroll();
  };

  ctx.onLyricsUserScroll = function onLyricsUserScroll() {
    if (ctx.lyricAutoScrollPaused()) ctx.pauseLyricAutoScroll();
  };

  ctx.onLyricsPointerdown = function onLyricsPointerdown(event) {
    if (event.target === event.currentTarget) ctx.pauseLyricAutoScroll();
  };

  ctx.onSeekPositionChange = function onSeekPositionChange(value) {
    if (!ctx.isSeeking.value) ctx.seek(value);
  };

  ctx.onSeekPan = function onSeekPan(phase) {
    if (phase === 'start') {
      ctx.isSeeking.value = true;
      ctx.seekPosition.value = ctx.currentTime.value;
      return;
    }

    if (phase === 'end') {
      ctx.seek(ctx.seekPosition.value);
      ctx.isSeeking.value = false;
    }
  };

  ctx.scrollActiveLyric = async function scrollActiveLyric({ force = false } = {}) {
    if (!ctx.activeLyricKey.value) return;
    if (!force && ctx.lyricAutoScrollPaused()) return;

    await nextTick();
    const lyricRoot = ctx.fullscreenPlayerOpen.value
      ? document.querySelector('.fullscreen-player__lyrics-scroll')
      : document;
    const activeLine = lyricRoot?.querySelector('.lyrics-pause--active, .lyrics-line--active');
    activeLine?.scrollIntoView?.({
      block: 'center',
      behavior: 'smooth'
    });
  };
}
