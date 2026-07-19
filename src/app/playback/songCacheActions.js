export function installSongCacheActions(ctx) {
  function playableCacheTracks() {
    const seen = new Set();
    return [ctx.activeTrack.value, ...ctx.queue.value]
      .filter((track) => ctx.isPlayableTrack(track) && track.mediaKind !== 'video')
      .filter((track) => {
        if (!track?.id || seen.has(track.id)) return false;
        seen.add(track.id);
        return true;
      });
  }

  function cachePayload(track) {
    return ctx.trackResolvePayload(track, { preload: true, mediaKind: 'audio' });
  }

  ctx.songCacheSettingsPayload = function songCacheSettingsPayload() {
    return {
      enabled: ctx.songCacheEnabled.value,
      maxSizeMb: ctx.songCacheMaxSizeMb.value
    };
  };

  ctx.formatBytes = function formatBytes(value) {
    const bytes = Number(value || 0);
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
  };

  ctx.songCacheUsageLabel = function songCacheUsageLabel() {
    return `${ctx.formatBytes(ctx.songCacheInventory.value.totalBytes)} of ${ctx.songCacheMaxSizeMb.value} MB`;
  };

  ctx.songCacheTrackCountLabel = function songCacheTrackCountLabel() {
    const count = ctx.songCacheInventory.value.entries.length;
    return `${count} cached ${count === 1 ? 'song' : 'songs'}`;
  };

  ctx.cachedSongTitle = function cachedSongTitle(entry = {}) {
    return entry.title && entry.title !== entry.videoId ? entry.title : 'Cached song';
  };

  ctx.cachedSongDetails = function cachedSongDetails(entry = {}) {
    return [entry.artist, ctx.formatBytes(entry.size)].filter(Boolean).join(' / ');
  };

  ctx.loadSongCacheInventory = async function loadSongCacheInventory() {
    if (!ctx.socket.value?.connected) return;

    ctx.songCacheLoading.value = true;
    try {
      ctx.songCacheInventory.value = await ctx.emitWithReply('playback:cache-list');
    } catch (error) {
      ctx.songCacheMessage.value = error.message;
    } finally {
      ctx.songCacheLoading.value = false;
    }
  };

  ctx.prefetchCurrentQueue = async function prefetchCurrentQueue() {
    if (!ctx.socket.value?.connected || !ctx.songCacheEnabled.value) return;
    const tracks = playableCacheTracks().map(cachePayload).filter(Boolean);
    if (!tracks.length) {
      ctx.songCacheMessage.value = 'Queue has no cacheable audio tracks.';
      return;
    }

    ctx.songCachePrefetching.value = true;
    ctx.songCacheMessage.value = `Caching ${tracks.length} ${tracks.length === 1 ? 'song' : 'songs'}...`;
    try {
      const result = await ctx.emitWithReply('playback:cache-prefetch', { tracks });
      ctx.songCacheInventory.value = result.cache;
      ctx.songCacheMessage.value = result.failed
        ? `Cached ${result.cached} of ${result.total}. ${result.failed} failed.`
        : `Cached ${result.cached} ${result.cached === 1 ? 'song' : 'songs'}.`;
    } catch (error) {
      ctx.songCacheMessage.value = error.message;
    } finally {
      ctx.songCachePrefetching.value = false;
    }
  };

  ctx.removeCachedSong = async function removeCachedSong(entry) {
    if (!ctx.socket.value?.connected || !entry?.key) return;

    ctx.songCacheLoading.value = true;
    try {
      ctx.songCacheInventory.value = await ctx.emitWithReply('playback:cache-remove', { key: entry.key });
      ctx.songCacheMessage.value = 'Removed cached song.';
    } catch (error) {
      ctx.songCacheMessage.value = error.message;
    } finally {
      ctx.songCacheLoading.value = false;
    }
  };

  ctx.clearSongCache = async function clearSongCache() {
    if (!ctx.socket.value?.connected) return;

    ctx.songCacheLoading.value = true;
    try {
      ctx.songCacheInventory.value = await ctx.emitWithReply('playback:cache-clear');
      ctx.songCacheMessage.value = 'Cleared song cache.';
    } catch (error) {
      ctx.songCacheMessage.value = error.message;
    } finally {
      ctx.songCacheLoading.value = false;
    }
  };

  ctx.syncSongCacheSettings = async function syncSongCacheSettings() {
    if (!ctx.socket.value?.connected) return;

    try {
      await ctx.emitWithReply('playback:cache-settings', ctx.songCacheSettingsPayload());
      await ctx.loadSongCacheInventory();
    } catch {
      // Cache settings should never block playback or the settings screen.
    }
  };
}
