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

import { computed } from 'vue';

function normalizedName(value = '') {
  return String(value || '').trim().toLowerCase();
}

function durationLabel(value) {
  const seconds = Math.max(0, Math.round(Number(value || 0)));
  if (!seconds) return '';
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

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

  function downloadTrackWithContext(track, detail = null) {
    const contextual = detail ? ctx.trackWithCollectionContext(track, detail) : { ...track };
    if (detail?.browseId && ['album', 'artist', 'playlist'].includes(detail.kind)) {
      contextual.downloadCollections = [{
        browseId: detail.browseId,
        kind: detail.kind,
        title: detail.title || '',
        artist: detail.artist || detail.subtitle || '',
        thumbnail: detail.thumbnail || ''
      }];
    }
    if (detail?.kind === 'artist') {
      contextual.artist ||= detail.title || detail.artist || '';
      contextual.artists = contextual.artists?.length ? contextual.artists : [contextual.artist].filter(Boolean);
      contextual.artistBrowseIds = [...new Set([
        ...(contextual.artistBrowseIds || []),
        detail.browseId
      ].filter(Boolean))];
      contextual.artistId = detail.browseId || contextual.artistId || '';
      contextual.artistBrowseId = detail.browseId || contextual.artistBrowseIds[0] || '';
    }
    if (detail?.kind === 'album') {
      contextual.album ||= detail.title || '';
      contextual.albumId ||= detail.browseId || '';
      contextual.artistBrowseIds = contextual.artistBrowseIds?.length
        ? contextual.artistBrowseIds
        : (detail.artistBrowseIds || [detail.artistBrowseId]).filter(Boolean);
    }
    return contextual;
  }

  function downloadedTrack(entry = {}, index = 0) {
    return {
      id: entry.videoId,
      title: entry.title || 'Downloaded song',
      artist: entry.artist || '',
      artists: entry.artist ? [entry.artist] : [],
      artistBrowseId: entry.artistId || entry.artistBrowseIds?.[0] || '',
      artistBrowseIds: entry.artistBrowseIds?.length
        ? entry.artistBrowseIds
        : [entry.artistId].filter(Boolean),
      album: entry.album || '',
      albumId: entry.albumId || '',
      thumbnail: entry.thumbnail || '',
      durationSeconds: Number(entry.durationSeconds || 0),
      duration: durationLabel(entry.durationSeconds),
      index: index + 1,
      type: 'song',
      mediaKind: 'audio',
      isAudioOnly: true,
      downloaded: true,
      downloadCollections: entry.collections || []
    };
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
    return `${ctx.formatBytes(ctx.songCacheInventory.value.cacheBytes)} replay cache of ${ctx.songCacheMaxSizeMb.value} MB / ${ctx.formatBytes(ctx.songCacheInventory.value.downloadedBytes)} downloaded`;
  };

  ctx.songCacheTrackCountLabel = function songCacheTrackCountLabel() {
    const downloaded = ctx.songCacheInventory.value.downloads?.length || 0;
    const cached = ctx.songCacheInventory.value.entries.length - downloaded;
    if (!downloaded) return `${cached} cached ${cached === 1 ? 'song' : 'songs'}`;
    return `${downloaded} downloaded / ${cached} cached`;
  };

  ctx.downloadedTracks = computed(() =>
    (ctx.songCacheInventory.value.downloads || []).map(downloadedTrack)
  );

  ctx.downloadedTrackIds = computed(() =>
    new Set(ctx.downloadedTracks.value.map((track) => track.id).filter(Boolean))
  );

  ctx.isTrackDownloaded = function isTrackDownloaded(track) {
    return Boolean(track?.id && ctx.downloadedTrackIds.value.has(track.id));
  };

  ctx.isTrackDownloading = function isTrackDownloading(track) {
    return Boolean(track?.id && ctx.downloadBusyTrackIds.value.includes(track.id));
  };

  ctx.downloadedEntriesForCollection = function downloadedEntriesForCollection(detail = {}) {
    const tracks = (detail.tracks || []).map((track) => downloadTrackWithContext(track, detail));
    if (tracks.length) {
      const ids = new Set(tracks.map((track) => track.id).filter(Boolean));
      return ctx.downloadedTracks.value.filter((track) => ids.has(track.id));
    }

    if (detail.kind === 'album') {
      return ctx.downloadedTracks.value.filter((track) =>
        (detail.browseId && track.albumId === detail.browseId) ||
        (detail.title && normalizedName(track.album) === normalizedName(detail.title))
      );
    }
    if (detail.kind === 'artist') {
      return ctx.downloadedTracks.value.filter((track) =>
        (detail.browseId && track.artistBrowseIds?.includes(detail.browseId)) ||
        (detail.title && normalizedName(track.artist) === normalizedName(detail.title))
      );
    }
    return [];
  };

  ctx.isCollectionDownloaded = function isCollectionDownloaded(detail = {}) {
    const tracks = (detail.tracks || [])
      .map((track) => downloadTrackWithContext(track, detail))
      .filter((track) => ctx.isPlayableTrack(track) && track.mediaKind !== 'video');
    if (!tracks.length || !tracks.every((track) => ctx.isTrackDownloaded(track))) return false;
    if (detail.kind !== 'playlist' || !detail.browseId) return true;
    const entries = new Map((ctx.songCacheInventory.value.downloads || []).map((entry) => [entry.videoId, entry]));
    return tracks.every((track) => entries.get(track.id)?.collections?.some((collection) =>
      collection.kind === 'playlist' && collection.browseId === detail.browseId
    ));
  };

  ctx.isCollectionDownloading = function isCollectionDownloading(detail = {}) {
    return ctx.downloadPreparingCollectionId.value === detail.browseId ||
      (detail.tracks || []).some((track) => ctx.isTrackDownloading(track));
  };

  ctx.cachedSongTitle = function cachedSongTitle(entry = {}) {
    return entry.title && entry.title !== entry.videoId ? entry.title : 'Cached song';
  };

  ctx.cachedSongDetails = function cachedSongDetails(entry = {}) {
    return [entry.artist, entry.downloaded ? 'Downloaded' : '', ctx.formatBytes(entry.size)].filter(Boolean).join(' / ');
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

  ctx.downloadTracks = async function downloadTracks(tracks, detail = null) {
    if (!ctx.socket.value?.connected) return;
    const contextualTracks = tracks
      .map((track) => downloadTrackWithContext(track, detail))
      .filter((track) => ctx.isPlayableTrack(track) && track.mediaKind !== 'video');
    const uniqueTracks = contextualTracks.filter((track, index, items) =>
      items.findIndex((candidate) => candidate.id === track.id) === index
    );
    if (!uniqueTracks.length) {
      ctx.downloadMessage.value = 'There are no downloadable songs here.';
      return;
    }

    const associatesCollection = Boolean(detail?.browseId && ['album', 'artist', 'playlist'].includes(detail.kind));
    const pending = associatesCollection
      ? uniqueTracks
      : uniqueTracks.filter((track) => !ctx.isTrackDownloaded(track));
    if (!pending.length) {
      ctx.downloadMessage.value = 'These songs are already downloaded.';
      return;
    }

    ctx.downloadBusyTrackIds.value = [...new Set([...ctx.downloadBusyTrackIds.value, ...pending.map((track) => track.id)])];
    ctx.downloadMessage.value = `Downloading ${pending.length} ${pending.length === 1 ? 'song' : 'songs'}…`;
    ctx.showShareMessage?.(ctx.downloadMessage.value);
    try {
      const payloads = pending.map(cachePayload).filter(Boolean);
      const result = await ctx.emitWithReply('playback:downloads-add', { tracks: payloads });
      ctx.songCacheInventory.value = result.inventory;
      ctx.downloadMessage.value = result.failed
        ? `Downloaded ${result.downloaded} of ${result.total}. ${result.failed} failed.`
        : `Downloaded ${result.downloaded} ${result.downloaded === 1 ? 'song' : 'songs'} for offline playback.`;
      ctx.showShareMessage?.(ctx.downloadMessage.value, Boolean(result.failed && !result.downloaded));
    } catch (error) {
      ctx.downloadMessage.value = error.message;
      ctx.showShareMessage?.(error.message, true);
    } finally {
      const completedIds = new Set(pending.map((track) => track.id));
      ctx.downloadBusyTrackIds.value = ctx.downloadBusyTrackIds.value.filter((id) => !completedIds.has(id));
    }
  };

  ctx.downloadTrack = function downloadTrack(track, detail = null) {
    return ctx.downloadTracks([track], detail);
  };

  ctx.prepareDownloadCollection = async function prepareDownloadCollection(detail = ctx.browseDetail.value) {
    if (detail?.kind === 'playlist' && detail.hasMoreTracks && ctx.browseDetail.value?.browseId === detail.browseId) {
      ctx.downloadMessage.value = 'Loading the full playlist…';
      ctx.showShareMessage?.(ctx.downloadMessage.value);
      void ctx.prefetchBrowseTrackPages();
      while (ctx.browseTrackPrefetching) {
        await new Promise((resolve) => window.setTimeout(resolve, 50));
      }
      if (ctx.browseDetail.value?.browseId === detail.browseId) return ctx.browseDetail.value;
    }
    return detail;
  };

  ctx.downloadCollection = async function downloadCollection(detail = ctx.browseDetail.value) {
    const browseId = detail?.browseId;
    if (!browseId || ctx.downloadPreparingCollectionId.value === browseId) return;
    ctx.downloadPreparingCollectionId.value = browseId;
    try {
      const prepared = await ctx.prepareDownloadCollection(detail);
      return await ctx.downloadTracks(prepared?.tracks || [], prepared);
    } finally {
      if (ctx.downloadPreparingCollectionId.value === browseId) ctx.downloadPreparingCollectionId.value = '';
    }
  };

  ctx.removeDownloads = async function removeDownloads(tracks = []) {
    if (!ctx.socket.value?.connected) return;
    const videoIds = [...new Set(tracks.map((track) => track?.id || track?.videoId).filter(Boolean))];
    if (!videoIds.length) return;

    ctx.downloadBusyTrackIds.value = [...new Set([...ctx.downloadBusyTrackIds.value, ...videoIds])];
    try {
      ctx.songCacheInventory.value = await ctx.emitWithReply('playback:downloads-remove', { videoIds });
      if (ctx.browseDetail.value?.offline) {
        const current = ctx.browseDetail.value;
        const refreshed = ctx.offlineBrowseDetail(current.kind, {
          ...current,
          offlineDownload: current.kind === 'playlist'
        });
        if (refreshed) ctx.browseDetail.value = refreshed;
        else ctx.selectView('home');
      }
      ctx.downloadMessage.value = `Removed ${videoIds.length} offline ${videoIds.length === 1 ? 'download' : 'downloads'}.`;
      ctx.showShareMessage?.(ctx.downloadMessage.value);
    } catch (error) {
      ctx.downloadMessage.value = error.message;
      ctx.showShareMessage?.(error.message, true);
    } finally {
      const removedIds = new Set(videoIds);
      ctx.downloadBusyTrackIds.value = ctx.downloadBusyTrackIds.value.filter((id) => !removedIds.has(id));
    }
  };

  ctx.removeTrackDownload = function removeTrackDownload(track) {
    return ctx.removeDownloads([track]);
  };

  ctx.removeCollectionDownloads = function removeCollectionDownloads(detail = ctx.browseDetail.value) {
    return ctx.removeDownloads(ctx.downloadedEntriesForCollection(detail));
  };

  ctx.toggleTrackDownload = function toggleTrackDownload(track, detail = null) {
    return ctx.isTrackDownloaded(track)
      ? ctx.removeTrackDownload(track)
      : ctx.downloadTrack(track, detail);
  };

  ctx.toggleCollectionDownload = function toggleCollectionDownload(detail = ctx.browseDetail.value) {
    return ctx.isCollectionDownloaded(detail)
      ? ctx.removeCollectionDownloads(detail)
      : ctx.downloadCollection(detail);
  };

  ctx.offlineBrowseDetail = function offlineBrowseDetail(kind, item = {}) {
    let tracks = [];
    if (kind === 'album') {
      tracks = ctx.downloadedTracks.value.filter((track) =>
        (ctx.itemBrowseId(item) && track.albumId === ctx.itemBrowseId(item)) ||
        normalizedName(track.album) === normalizedName(item.title)
      );
    } else if (kind === 'artist') {
      tracks = ctx.downloadedTracks.value.filter((track) =>
        (ctx.itemBrowseId(item) && track.artistBrowseIds?.includes(ctx.itemBrowseId(item))) ||
        normalizedName(track.artist) === normalizedName(item.title)
      );
    } else if (kind === 'playlist') {
      const browseId = ctx.itemBrowseId(item);
      tracks = browseId === 'offline_downloads'
        ? ctx.downloadedTracks.value
        : ctx.downloadedTracks.value.filter((track) => track.downloadCollections?.some((collection) =>
          collection.kind === 'playlist' && collection.browseId === browseId
        ));
    }
    if (!tracks.length) return null;

    const title = item.title || (kind === 'album' ? tracks[0].album : kind === 'artist' ? tracks[0].artist : 'Downloaded Music');
    return {
      browseId: ctx.itemBrowseId(item) || `offline-${kind}-${normalizedName(title).replace(/[^a-z0-9]+/g, '-')}`,
      title,
      artist: kind === 'artist' ? title : (item.artist || tracks[0].artist || ''),
      artistBrowseId: kind === 'album' ? (tracks[0].artistBrowseId || '') : '',
      artistBrowseIds: kind === 'album' ? (tracks[0].artistBrowseIds || []) : [],
      subtitle: `${tracks.length} downloaded ${tracks.length === 1 ? 'song' : 'songs'}`,
      itemCount: `${tracks.length} tracks`,
      totalTrackCount: tracks.length,
      thumbnail: item.thumbnail || tracks.find((track) => track.thumbnail)?.thumbnail || '',
      kind,
      tracks: tracks.map((track, index) => ({ ...track, index: index + 1 })),
      sections: [],
      offline: true
    };
  };

  ctx.removeCachedSong = async function removeCachedSong(entry) {
    if (!ctx.socket.value?.connected || !entry?.key) return;
    if (entry.downloaded) return ctx.removeDownloads([{ id: entry.videoId }]);

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
      ctx.songCacheMessage.value = 'Cleared replay cache. Offline downloads were kept.';
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
