/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue';
import { io } from 'socket.io-client';
import { bindSettingsStorageSync } from './settingsStorageSync.js';

export function disableCrossfadePlayback(ctx) {
  ctx.stopCrossfadeClock();
  if (ctx.cancelActiveCrossfade) {
    ctx.cancelActiveCrossfade('crossfade-disabled');
  } else {
    ctx.autoCrossfade?.cancel?.();
    ctx.wsolaCrossfade?.cancel?.('crossfade-disabled');
  }
  ctx.dismissSmartCrossfadeMix?.();
  ctx.setCurrentAudioVolume();
}

export function installLifecycle(ctx) {
  watch(ctx.volume, (value) => {
    ctx.autoCrossfade.setTargetVolume(value);
    ctx.wsolaCrossfade?.setTargetVolume?.(value);
    ctx.setCurrentAudioVolume(value);
  });

  watch(ctx.selectedFilter, () => {
    if (ctx.restoringNavigation) return;
    if (ctx.activeView.value === 'search') ctx.runSearch();
  });

  watch(ctx.activeView, async (view) => {
    ctx.scheduleSupportPolling();
    if (view === 'support') await ctx.loadSupportReports({ quiet: true });
    if (view !== 'browse') {
      ctx.cancelBrowseTrackPrefetch();
      return;
    }

    await nextTick();
    void ctx.prefetchBrowseTrackPages();
  });

  watch(ctx.query, () => {
    if (ctx.consumeRestoredQueryChange?.()) return;
    if (ctx.restoringNavigation) return;
    ctx.queueSearch();
  });

  watch([
    ctx.activeView,
    ctx.browseOrigin,
    ctx.browseDetail,
    ctx.sectionMoreDetail,
    ctx.query,
    ctx.searchResult,
    ctx.selectedFilter
  ], () => {
    if (!ctx.restoringNavigation && !ctx.browseTrackPrefetching) ctx.writeLastPageEntry();
  }, { deep: true });

  watch(ctx.activeTrack, (track) => {
    ctx.handleSleepTimerTrackChange(track);
    ctx.loadEnhancedArtwork(track);
    ctx.loadLyrics(track);
    ctx.loadSponsorBlockSegments(track);
    ctx.applyMediaSessionMetadata();
    ctx.queueSystemMediaSync();
    ctx.queueDiscordPresenceSync();
    if (ctx.isPlaying.value) ctx.startLastfmTrack();

    if (!ctx.activeTrack.value) {
      ctx.clearMediaSessionPositionState();
    }
  }, { immediate: true });

  watch(() => ctx.albumDetailArtworkLookupKey(ctx.browseDetail.value), () => {
    ctx.loadDetailEnhancedArtwork(ctx.browseDetail.value);
  }, { immediate: true });

  // Cached artwork entries were shaped by whatever the switch said when they
  // were fetched, so both directions need a re-lookup: turning motion off has to
  // drop covers already playing, and turning it back on has to go get them.
  watch(ctx.animatedArtworkEnabled, () => {
    ctx.artworkCache.clear();
    ctx.loadEnhancedArtwork(ctx.activeTrack.value);
    ctx.loadDetailEnhancedArtwork(ctx.browseDetail.value);
  });

  watch(() => ctx.playlistArtworkCollageLookupKey(ctx.browseDetail.value), () => {
    ctx.loadPlaylistArtworkCollage(ctx.browseDetail.value);
  }, { immediate: true });

  watch(ctx.nowArtworkVideo, (videoUrl) => {
    if (videoUrl) ctx.syncNowArtworkVideoPlayback();
    ctx.queueDiscordPresenceSync();
  });

  watch(ctx.detailArtworkVideo, (videoUrl) => {
    if (videoUrl) ctx.playDetailArtworkVideo();
  });

  watch(ctx.nowArtworkImage, (imageUrl) => {
    ctx.applyMediaSessionMetadata();
    ctx.queueSystemMediaSync();
  }, { immediate: true });

  watch(ctx.backdropArtworkImage, (imageUrl) => {
    if (imageUrl) ctx.lastImmersiveArtworkImage.value = imageUrl;
    ctx.loadArtworkColors(imageUrl);
  }, { immediate: true });

  watch(ctx.discordArtworkImage, () => {
    ctx.queueDiscordPresenceSync();
  }, { immediate: true });

  watch(ctx.isPlaying, () => {
    ctx.syncNowArtworkVideoPlayback();
    ctx.updateMediaSessionPlaybackState();
    ctx.queueSystemMediaSync();
    ctx.queueDiscordPresenceSync();
    if (ctx.isPlaying.value) ctx.startCrossfadeClock();
    else ctx.stopCrossfadeClock();
    if (ctx.isPlaying.value) ctx.startLastfmTrack();
  }, { immediate: true });

  watch([
    ctx.accentColorSource,
    ctx.autoplayEnabled,
    ctx.closeToTrayEnabled,
    ctx.crossfadeEnabled,
    ctx.crossfadeMode,
    ctx.crossfadeSeconds,
    ctx.customArtistPagesEnabled,
    ctx.customAccentColor,
    ctx.youtubeHistoryEnabled,
    ctx.discordRpcEnabled,
    ctx.graphicsMode,
    ctx.immersiveBackgroundsEnabled,
    ctx.immersiveBackgroundIntensity,
    ctx.immersiveBackgroundMotion,
    ctx.layoutPreset,
    ctx.keepOldVersions,
    ctx.uiScale,
    ctx.playbackStatePersistenceEnabled,
    ctx.queueLayout,
    ctx.songCacheEnabled,
    ctx.songCacheMaxSizeMb,
    ctx.sponsorBlockMode,
    ctx.streamQuality,
    ctx.videoPlaybackEnabled,
    ctx.animatedArtworkEnabled,
    ctx.volumeNormalizationEnabled,
    ctx.repeatMode,
    ctx.shuffleEnabled,
    ctx.themePreference,
    ctx.volume
  ], () => {
    ctx.queueSystemMediaSync();
    ctx.crossfadeSeconds.value = ctx.normalizeUserPreferences({
      crossfadeSeconds: ctx.crossfadeSeconds.value
    }).crossfadeSeconds;
    ctx.crossfadeMode.value = ctx.normalizeUserPreferences({
      crossfadeMode: ctx.crossfadeMode.value
    }).crossfadeMode;
    ctx.repeatMode.value = ctx.normalizeUserPreferences({
      repeatMode: ctx.repeatMode.value
    }).repeatMode;
    ctx.volume.value = ctx.normalizeUserPreferences({
      volume: ctx.volume.value
    }).volume;
    ctx.songCacheMaxSizeMb.value = ctx.normalizeUserPreferences({
      songCacheMaxSizeMb: ctx.songCacheMaxSizeMb.value
    }).songCacheMaxSizeMb;
    ctx.writeUserPreferences({
      accentColorSource: ctx.accentColorSource.value,
      autoplayEnabled: ctx.autoplayEnabled.value,
      closeToTrayEnabled: ctx.closeToTrayEnabled.value,
      crossfadeEnabled: ctx.crossfadeEnabled.value,
      crossfadeMode: ctx.crossfadeMode.value,
      crossfadeSeconds: ctx.crossfadeSeconds.value,
      customArtistPagesEnabled: ctx.customArtistPagesEnabled.value,
      customAccentColor: ctx.customAccentColor.value,
      youtubeHistoryEnabled: ctx.youtubeHistoryEnabled.value,
      discordRpcEnabled: ctx.discordRpcEnabled.value,
      graphicsMode: ctx.graphicsMode.value,
      immersiveBackgroundsEnabled: ctx.immersiveBackgroundsEnabled.value,
      immersiveBackgroundIntensity: ctx.immersiveBackgroundIntensity.value,
      immersiveBackgroundMotion: ctx.immersiveBackgroundMotion.value,
      layoutPreset: ctx.layoutPreset.value,
      keepOldVersions: ctx.keepOldVersions.value,
      uiScale: ctx.uiScale.value,
      playbackStatePersistenceEnabled: ctx.playbackStatePersistenceEnabled.value,
      queueLayout: ctx.queueLayout.value,
      songCacheEnabled: ctx.songCacheEnabled.value,
      songCacheMaxSizeMb: ctx.songCacheMaxSizeMb.value,
      sponsorBlockMode: ctx.sponsorBlockMode.value,
      streamQuality: ctx.streamQuality.value,
      videoPlaybackEnabled: ctx.videoPlaybackEnabled.value,
      animatedArtworkEnabled: ctx.animatedArtworkEnabled.value,
      volumeNormalizationEnabled: ctx.volumeNormalizationEnabled.value,
      repeatMode: ctx.repeatMode.value,
      shuffleEnabled: ctx.shuffleEnabled.value,
      themePreference: ctx.themePreference.value,
      volume: ctx.volume.value
    });
  }, { immediate: true });

  // Split by cost, not by taste. `history` is capped at thirty and is mutated
  // in place (shift/unshift), so it has to be watched deeply. The queue and its
  // pre-shuffle source now run to thousands of tracks on a shuffled playlist,
  // and deep-watching those means re-traversing every track object on every
  // skip -- they are only ever replaced, so identity is enough.
  watch([ctx.queue, ctx.shuffleSourceQueue], () => {
    ctx.writePlaybackState();
  }, { immediate: true });

  watch([ctx.activeTrack, ctx.history], () => {
    ctx.writePlaybackState();
  }, { deep: true, immediate: true });

  watch(ctx.autoplayEnabled, (enabled) => {
    ctx.autoplayRequest += 1;
    ctx.autoplayRequestPromise = null;
    ctx.autoplayLoading.value = false;
    ctx.autoplayError.value = '';

    if (enabled) void ctx.ensureAutoplayQueue();
    else ctx.removeAutoplayTracks();
  });

  watch(ctx.customArtistPagesEnabled, (enabled) => {
    if (enabled) void ctx.loadCustomArtistPages?.();
  });

  watch(ctx.playbackStatePersistenceEnabled, (enabled) => {
    if (enabled) {
      ctx.writePlaybackState();
      return;
    }

    ctx.clearPlaybackState();
  }, { immediate: true });

  watch(ctx.youtubeHistoryEnabled, (enabled) => {
    if (!enabled) ctx.finishYouTubeHistory?.();
  });

  watch(() => [
    ctx.activeTrack.value?.id || '',
    ctx.queue.value.map((track) => track.id).join(','),
    ctx.socketState.value
  ], () => {
    void ctx.ensureAutoplayQueue();
  });

  watch(ctx.crossfadeSeconds, (seconds) => {
    ctx.autoCrossfade.setFadeSeconds(seconds);
  }, { immediate: true });

  watch(ctx.crossfadeMode, (mode) => {
    ctx.autoCrossfade.setMode(mode);
    if (mode !== 'smart') return;
    const active = ctx.activeTrack.value;
    const prepared = ctx.nextTrackPreload.value;
    if (active?.id && active.streamUrl) {
      void ctx.analyzeCurrentCrossfadeTrack(active, active.streamUrl, ctx.duration.value);
    }
    if (prepared?.track?.id && prepared.resolved?.streamUrl) {
      void ctx.analyzeNextCrossfadeTrack(
        prepared.track,
        prepared.resolved.streamUrl,
        prepared.track.durationSeconds || 0
      );
    }
  }, { immediate: true });

  watch(ctx.crossfadeEnabled, (enabled) => {
    if (enabled) {
      if (ctx.isPlaying.value) ctx.startCrossfadeClock();
      return;
    }
    disableCrossfadePlayback(ctx);
  });

  // The main process decides what a window close means, and it only knows the
  // preference because the renderer hands it over -- so send it on startup too.
  watch(ctx.closeToTrayEnabled, (enabled) => {
    void window.orchardDesktopControls?.setCloseToTray?.(enabled);
  }, { immediate: true });

  watch(ctx.discordRpcEnabled, (enabled) => {
    if (enabled) ctx.queueDiscordPresenceSync();
    else ctx.syncDiscordPresence();
  });

  watch(ctx.graphicsMode, async (value) => {
    const normalized = ctx.normalizeUserPreferences({ graphicsMode: value }).graphicsMode;
    if (normalized !== value) {
      ctx.graphicsMode.value = normalized;
      return;
    }

    try {
      const state = await window.orchardApp?.graphicsMode?.(normalized);
      if (!state) {
        ctx.graphicsModeApplied.value = normalized;
        ctx.graphicsModeStatusReady.value = true;
        return;
      }

      ctx.graphicsModeApplied.value = state.appliedMode;
      ctx.graphicsModeIntegratedSupported.value = Boolean(state.integratedGpuSupported);
      ctx.graphicsModePlatform.value = state.platform || '';
      ctx.graphicsModeStatusReady.value = true;
      ctx.graphicsModeMessage.value = '';
      if (state.selectedMode !== normalized) ctx.graphicsMode.value = state.selectedMode;
    } catch (error) {
      ctx.graphicsModeStatusReady.value = true;
      ctx.graphicsModeMessage.value = error.message || 'Could not save the graphics mode.';
    }
  }, { immediate: true });

  ctx.restartOrchard = async function restartOrchard() {
    try {
      await window.orchardApp?.restart?.();
    } catch (error) {
      ctx.graphicsModeMessage.value = error.message || 'Could not restart Orchard.';
    }
  };

  watch([ctx.songCacheEnabled, ctx.songCacheMaxSizeMb], () => {
    ctx.syncSongCacheSettings();
  });

  // The veil is solved against the theme's text colours and scaled by the
  // background intensity, so both have to re-solve it, not just the accent.
  watch([
    ctx.accentColorSource,
    ctx.customAccentColor,
    ctx.themePreference,
    ctx.immersiveBackgroundIntensity
  ], () => {
    ctx.loadArtworkColors(ctx.backdropArtworkImage.value);
  });

  watch(ctx.themePreference, () => {
    ctx.applyThemePreference();
  });

  watch(ctx.layoutPreset, () => {
    ctx.applyLayoutPreset();
  }, { immediate: true });

  watch(ctx.uiScale, (scale) => {
    if (window.orchardWindow?.setZoomFactor) window.orchardWindow.setZoomFactor(scale);
    else document.documentElement.style.zoom = String(scale);
  }, { immediate: true });

  watch(ctx.sponsorBlockMode, (mode, previousMode) => {
    if (mode === 'off') {
      ctx.clearSponsorBlockSegments();
      return;
    }

    // Switching between the two on states keeps the segments already fetched.
    if (previousMode === 'off') ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
    else ctx.maybeAutoSkipSponsorSegment();
  });

  watch(ctx.volumeNormalizationEnabled, (enabled) => {
    [ctx.audioRef.value, ctx.nextAudioRef.value, ctx.videoRef.value, ctx.videoAudioRef.value]
      .forEach((element) => ctx.setAudioNormalization(element, enabled));
  });

  watch([ctx.currentTime, ctx.duration], () => {
    ctx.maybeAutoSkipSponsorSegment();
    ctx.updateMediaSessionPositionState();
    ctx.queueSystemMediaSync();
    ctx.queueConnectSync();
  });

  watch(ctx.activeLyricKey, () => {
    ctx.scrollActiveLyric();
  });

  watch([ctx.duration, ctx.buffering, ctx.playbackError], () => {
    ctx.queueSystemMediaSync();
    ctx.queueDiscordPresenceSync();
  });

  watch([ctx.queue, ctx.history], () => {
    ctx.queueSystemMediaSync();
    ctx.queueConnectSync();
  }, { deep: true });

  watch([
    ctx.activeTrack,
    ctx.isPlaying,
    ctx.buffering,
    ctx.volume,
    ctx.lyricsState,
    ctx.nowArtworkImage,
    ctx.nowArtworkVideo,
    ctx.audioEngineConfig
  ], () => {
    ctx.queueConnectSync();
  }, { deep: true });

  onMounted(() => {
    ctx.syncViewportSize();
    ctx.unbindSettingsStorageSync = bindSettingsStorageSync(ctx, window, window.orchardApp);
    ctx.bindSystemThemePreference();
    window.addEventListener('resize', ctx.syncViewportSize);
    document.addEventListener('fullscreenchange', ctx.onFullscreenPlayerChange);
    ctx.registerMediaSessionHandlers();
    window.addEventListener('keydown', ctx.onGlobalKeydown);
    ctx.applyMediaSessionMetadata();
    ctx.updateMediaSessionPlaybackState();
    ctx.updateMediaSessionPositionState();
    void ctx.loadProxyMode();
    void ctx.bindUpdateEvents().then((bound) => {
      if (bound && new URLSearchParams(window.location.search).get('welcome') !== '1') {
        void ctx.ensureOfficialArtistPages();
      }
    });
    ctx.bindSystemMediaEvents();
    ctx.bindDesktopControls();
    ctx.showChangelogAfterUpgrade();
    if (new URLSearchParams(window.location.search).get('welcome') !== '1') {
      ctx.loadSupportReports({ quiet: true });
      ctx.scheduleSupportPolling();
      window.addEventListener('focus', ctx.refreshSupportOnFocus);
    }

    ctx.socket.value = io(`http://127.0.0.1:${ctx.socketPort()}`, {
      transports: ['websocket']
    });

    ctx.socket.value.on('connect', async () => {
      ctx.socketState.value = 'connected';
      ctx.bindOrchardConnectEvents();
      ctx.loadOrchardConnectInfo().catch(() => {});
      await ctx.syncSongCacheSettings();
      ctx.queueConnectSync();
      ctx.reviewVersionUpgrade();
      await ctx.fetchAuthStatus();
      if (ctx.activeView.value === 'search' && ctx.query.value.trim()) ctx.runSearch();
      if (ctx.activeTrack.value) ctx.loadSponsorBlockSegments(ctx.activeTrack.value);
      if (ctx.activeView.value === 'releaseRadar') ctx.loadReleaseRadar({ force: true });
    });

    ctx.socket.value.on('auth:state', async (state) => {
      const wasSignedIn = ctx.authState.value.signedIn;
      ctx.syncAuthState(state);
      if (state.signedIn && !wasSignedIn) await ctx.loadHomeLibrary();
    });

    ctx.socket.value.on('music:playlist:editable', ({ browseId, editable }) => {
      const currentId = String(ctx.browseDetail.value?.browseId || '').replace(/^VL/, '');
      const updatedId = String(browseId || '').replace(/^VL/, '');
      if (ctx.browseDetail.value?.kind !== 'playlist' || !currentId || currentId !== updatedId) return;
      ctx.browseDetail.value = { ...ctx.browseDetail.value, editable: Boolean(editable) };
    });

    ctx.socket.value.on('disconnect', () => {
      ctx.socketState.value = 'offline';
    });

    ctx.socket.value.on('connect_error', (error) => {
      ctx.socketState.value = 'offline';
      ctx.errorMessage.value = error.message;
    });
  });

  // Vue disposes watchers with this component scope. Explicit teardown below
  // owns global listeners, timers, workers, AudioContexts, and the socket.
  onBeforeUnmount(() => {
    window.clearTimeout(ctx.searchDebounceTimer);
    window.clearTimeout(ctx.collectionQuickSearchTimer);
    window.clearTimeout(ctx.spotlightSearchTimer);
    window.clearTimeout(ctx.lyricAutoScrollPauseTimer);
    window.clearTimeout(ctx.discordPresenceSyncTimer);
    window.clearTimeout(ctx.orchardConnectSyncTimer);
    ctx.cancelBrowseTrackPrefetch();
    ctx.crossfadeAnalysisAbort?.abort();
    ctx.nextCrossfadeAnalysisAbort?.abort();
    ctx.stopCrossfadeClock();
    ctx.dismissSmartCrossfadeMix?.();
    ctx.autoplayRequest += 1;
    ctx.autoCrossfade.cancel();
    ctx.wsolaCrossfade?.cancel?.('app-teardown');
    ctx.finishYouTubeHistory?.();
    ctx.destroySleepTimer();
    ctx.audioAnalyzer.destroy();
    ctx.smartCrossfadeAnalyzer.destroy();
    ctx.clearSystemThemePreference();
    window.orchardDiscord?.clearPresence?.();
    ctx.updateUnsubscribe?.();
    ctx.clearSystemMediaEvents();
    ctx.clearDesktopControls();
    ctx.stopSupportPolling();
    ctx.clearMediaSessionHandlers();
    ctx.unbindSettingsStorageSync?.();
    window.removeEventListener('keydown', ctx.onGlobalKeydown);
    window.removeEventListener('resize', ctx.syncViewportSize);
    window.removeEventListener('focus', ctx.refreshSupportOnFocus);
    document.removeEventListener('fullscreenchange', ctx.onFullscreenPlayerChange);
    ctx.socket.value?.disconnect();
  });
}
