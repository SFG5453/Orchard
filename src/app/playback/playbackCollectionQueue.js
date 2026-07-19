import { nextTick } from 'vue';

function itemCountNumber(value = '') {
  return Number(String(value).match(/([\d,]+)\s+(?:songs?|tracks?|videos?)\b/i)?.[1]?.replace(/,/g, '')) || 0;
}

export function unusedPlaylistTracks({
  allTracks = [],
  queue = [],
  activeTrack = null,
  history = [],
  playedTrackIds = []
} = {}) {
  const excludedIds = new Set([
    ...queue,
    activeTrack,
    ...history,
    ...playedTrackIds
  ].map((track) => track?.id || track).filter(Boolean));

  return allTracks.filter((track) => track?.id && !excludedIds.has(track.id));
}

export function playlistPlayedTrackIds(allTracks = [], activeTrackId = '') {
  const activeIndex = allTracks.findIndex((track) => track.id === activeTrackId);
  const tracksThroughActive = activeIndex >= 0 ? allTracks.slice(0, activeIndex + 1) : [];
  return [...new Set(tracksThroughActive.map((track) => track.id).filter(Boolean))];
}

export function playlistPreviousState(allTracks = [], activeTrackId = '') {
  const activeIndex = allTracks.findIndex((track) => track.id === activeTrackId);
  return {
    activeIndex,
    previousTrack: activeIndex > 0 ? allTracks[activeIndex - 1] : null
  };
}

function cryptoRandomInt(maxExclusive) {
  const nodeRandomInt = globalThis.orchardCrypto?.randomInt;
  if (typeof nodeRandomInt === 'function') return nodeRandomInt(maxExclusive);

  const getRandomValues = globalThis.crypto?.getRandomValues?.bind(globalThis.crypto);
  if (typeof getRandomValues !== 'function') throw new Error('Crypto random values are unavailable');

  const range = 0x100000000;
  const limit = Math.floor(range / maxExclusive) * maxExclusive;
  const buffer = new Uint32Array(1);
  do {
    getRandomValues(buffer);
  } while (buffer[0] >= limit);
  return buffer[0] % maxExclusive;
}

export function installPlaybackCollectionQueue(ctx) {
  ctx.markPlaylistTrackPlayed = function markPlaylistTrackPlayed(track) {
    const context = ctx.playbackPlaylistContext.value;
    if (!context?.browseId || !track?.id || !context.allTracks?.some((item) => item.id === track.id)) return;
    context.playedTrackIds ||= [];
    if (!context.playedTrackIds.includes(track.id)) context.playedTrackIds.push(track.id);
  };

  ctx.shuffleItems = function shuffleItems(items) {
    const copy = [...items];
    for (let i = copy.length - 1; i > 0; i--) {
      const j = cryptoRandomInt(i + 1);
      [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
  };

  ctx.playCollection = async function playCollection(detail, options = {}) {
    const playableDetail = options.shuffle && detail?.kind === 'playlist' && detail.hasMoreTracks
      ? await ctx.loadBrowseTracksUntil(101, detail)
      : detail;
    let tracks = ctx.tracksWithCollectionContext(playableDetail).filter(ctx.isPlayableTrack);
    if (options.shuffle) {
      const seen = new Set();
      const unique = [];
      for (const t of tracks) {
        if (t.id && !seen.has(t.id)) {
          seen.add(t.id);
          unique.push(t);
          if (unique.length >= 101) break;
        }
      }
      tracks = unique;
    } else {
      tracks = tracks.slice(0, 101);
    }
    if (!tracks.length) return;

    if (options.shuffle) ctx.shuffleEnabled.value = true;
    const orderedTracks = options.shuffle ? ctx.shuffleItems(tracks) : tracks;
    ctx.playTrack(orderedTracks[0], {
      queueSource: orderedTracks,
      queueAlreadyShuffled: Boolean(options.shuffle),
      resetHistory: detail?.kind === 'playlist'
    });
  };

  ctx.collectionQueueOrigin = function collectionQueueOrigin(detail) {
    if (!detail?.kind || !detail.title) return null;

    const artist = detail.artist || detail.subtitle || '';
    return {
      kind: detail.futureAlbumId ? 'album' : detail.kind,
      title: detail.title,
      artist,
      totalTrackCount: Number(detail.totalTrackCount) || itemCountNumber(detail.itemCount)
    };
  };

  ctx.rightQueueCountLabel = function rightQueueCountLabel() {
    const queued = ctx.queue.value.length;
    const origin = ctx.activeTrack.value?.queueOrigin;
    const browseTotal = origin?.title === ctx.browseDetail.value?.title
      ? Number(ctx.browseDetail.value?.totalTrackCount) || itemCountNumber(ctx.browseDetail.value?.itemCount)
      : 0;
    const total = Number(origin?.totalTrackCount) || browseTotal;
    return total > queued
      ? `${queued} queued (${total.toLocaleString()} total)`
      : `${queued} queued`;
  };

  ctx.queueOriginLabel = function queueOriginLabel(origin) {
    if (!origin?.title) return '';

    if (origin.kind === 'album') {
      return [origin.title, origin.artist].filter(Boolean).join(' / ');
    }

    return origin.title;
  };

  ctx.trackQueueOriginLabel = function trackQueueOriginLabel(track) {
    return ctx.queueOriginLabel(track?.queueOrigin);
  };

  ctx.trackWithCollectionContext = function trackWithCollectionContext(track, detail) {
    const queueOrigin = ctx.collectionQueueOrigin(detail);

    if (detail?.kind !== 'album') {
      const collectionTrack = queueOrigin ? { ...track, queueOrigin } : { ...track };
      if (['playlist', 'podcast'].includes(detail?.kind)) collectionTrack.mediaKind = 'audio';
      return collectionTrack;
    }

    const isFutureAlbum = Boolean(detail.futureAlbumId);

    return {
      ...track,
      queueOrigin,
      album: track.album || detail.title || '',
      albumId: track.albumId || (isFutureAlbum ? '' : detail.browseId) || '',
      futureAlbumId: track.futureAlbumId || detail.futureAlbumId || null,
      futureAlbumUrl: track.futureAlbumUrl || detail.futureAlbumUrl || '',
      artist: track.artist || detail.artist || detail.subtitle || '',
      artists: track.artists?.length ? track.artists : [detail.artist || detail.subtitle].filter(Boolean),
      thumbnail: ctx.collectionTrackCover(track, detail),
      mediaKind: track.mediaKind || 'audio'
    };
  };

  ctx.tracksWithCollectionContext = function tracksWithCollectionContext(detail) {
    return (detail?.tracks || []).map((track) => ctx.trackWithCollectionContext(track, detail));
  };

  ctx.albumVideoItem = function albumVideoItem(track, detail) {
    return {
      ...ctx.trackWithCollectionContext(track, detail),
      type: 'video',
      mediaKind: 'video',
      isAudioOnly: false,
      thumbnail: track.id ? `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg` : track.thumbnail || detail?.thumbnail || ''
    };
  };

  ctx.dedupeAlbumVideos = function dedupeAlbumVideos(items = []) {
    const seen = new Set();

    return items
      .filter(ctx.isPlayableTrack)
      .map((item) => ({
        ...item,
        type: 'video',
        mediaKind: 'video',
        isAudioOnly: false
      }))
      .filter((item) => {
        const key = item.id || ctx.normalizedLookupText(`${item.title} ${item.artist || item.subtitle || ''}`);
        if (!key || seen.has(key)) return false;
        seen.add(key);
        return true;
      });
  };

  ctx.refillPlaylistQueue = async function refillPlaylistQueue() {
    const context = ctx.playbackPlaylistContext.value;
    if (!context || !context.browseId) return;

    if (ctx.queue.value.length >= 80) return;

    const getUnusedTracks = () => unusedPlaylistTracks({
      allTracks: context.allTracks,
      queue: ctx.queue.value,
      activeTrack: ctx.activeTrack.value,
      history: ctx.history.value,
      playedTrackIds: context.playedTrackIds
    });

    let unused = getUnusedTracks();

    while (unused.length < (100 - ctx.queue.value.length) && context.hasMoreTracks) {
      const continuation = context.continuation;
      if (!continuation) break;

      try {
        const data = await ctx.emitWithReply('music:playlist:more', {
          browseId: context.browseId,
          continuation: continuation,
          startIndex: context.allTracks.length
        });

        if (!data || context.browseId !== ctx.playbackPlaylistContext.value?.browseId) {
          break;
        }

        context.continuation = data.continuation || '';
        context.hasMoreTracks = Boolean(data.hasMoreTracks && data.continuation && data.continuation !== continuation);

        const seenIds = new Set(context.allTracks.map((t) => t.id).filter(Boolean));
        const newTracks = (data.tracks || [])
          .map((t) => ctx.trackWithCollectionContext(t, { browseId: context.browseId, kind: 'playlist' }))
          .filter((t) => ctx.isPlayableTrack(t) && t.id && !seenIds.has(t.id));

        if (!newTracks.length && !context.hasMoreTracks) {
          break;
        }

        context.allTracks.push(...newTracks);
        unused = getUnusedTracks();
      } catch (error) {
        console.error('Failed to load more tracks for queue refill:', error);
        break;
      }
    }

    const needed = 100 - ctx.queue.value.length;
    if (needed <= 0 || !unused.length) return;

    let toAdd = unused.slice(0, needed);
    if (context.shuffled) {
      toAdd = ctx.shuffleItems(toAdd);
    }

    ctx.queue.value = [...ctx.queue.value, ...toAdd];
    if (ctx.shuffleEnabled.value) {
      const addedIds = new Set(toAdd.map((t) => t.id));
      const originalAdded = unused.filter((t) => addedIds.has(t.id));
      ctx.shuffleSourceQueue.value = [...ctx.shuffleSourceQueue.value, ...originalAdded];
    }
    ctx.clearNextPreload();
    void nextTick(() => ctx.preloadNextTrack());
  };
}
