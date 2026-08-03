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

// How many upcoming tracks the background walk leaves alone when it folds a new
// page into the queue. Everything past this point is reshuffled with the new
// arrivals; everything before it is what the listener is already reading as
// "up next", and having that churn under them every time a page lands is worse
// than the slight bias it costs -- the pinned few were drawn from a smaller
// pool than the rest of the playlist.
const SHUFFLE_PINNED_LOOKAHEAD = 20;

/**
 * Folds newly loaded tracks into the unplayed tail of an already shuffled queue.
 * The tail and the arrivals are shuffled together rather than appended, so the
 * result is a uniform shuffle of everything still to play -- a playlist's last
 * page is as likely to come up next as its first.
 */
export function mergeShuffledTail(queue = [], incoming = [], pinned = SHUFFLE_PINNED_LOOKAHEAD, shuffle = (items) => items) {
  if (!incoming.length) return queue;
  const head = queue.slice(0, pinned);
  const tail = queue.slice(pinned);
  return [...head, ...shuffle([...tail, ...incoming])];
}

export function installPlaybackCollectionQueue(ctx) {
  // Only one walk runs at a time. A new one supersedes whatever is in flight by
  // bumping the token, which the loop checks after every await.
  let backfillToken = 0;

  ctx.stopPlaylistBackfill = function stopPlaylistBackfill() {
    backfillToken += 1;
    if (ctx.playlistBackfill.value.active) {
      ctx.playlistBackfill.value = { ...ctx.playlistBackfill.value, active: false };
    }
  };

  /**
   * Walks a shuffled playlist's remaining continuation pages behind playback and
   * folds each one into the queue as it lands.
   *
   * YouTube serves playlists as a chain of opaque continuation tokens with no
   * random access, so the only way to shuffle across the whole thing is to hold
   * the whole thing. Doing that before the first note plays would stall startup
   * by one round trip per hundred tracks, so playback starts on the first page
   * and the shuffle widens underneath it instead.
   */
  ctx.backfillPlaylistQueue = async function backfillPlaylistQueue() {
    const context = ctx.playbackPlaylistContext.value;
    if (!context?.browseId || !context.shuffled) return;

    ctx.stopPlaylistBackfill();
    const token = backfillToken;
    const browseId = context.browseId;
    const stale = () => token !== backfillToken || ctx.playbackPlaylistContext.value?.browseId !== browseId;

    // Folds whatever is not in the queue yet into its unplayed tail.
    const absorb = (incoming) => {
      if (!incoming.length) return;
      ctx.queue.value = mergeShuffledTail(ctx.queue.value, incoming, SHUFFLE_PINNED_LOOKAHEAD, ctx.shuffleItems);
      // Un-shuffle restores playlist order, so the source list grows in the
      // order the pages arrived rather than the order the queue holds them.
      if (ctx.shuffleEnabled.value) {
        ctx.shuffleSourceQueue.value = [...ctx.shuffleSourceQueue.value, ...incoming];
      }
    };

    const unqueued = () => unusedPlaylistTracks({
      allTracks: context.allTracks,
      queue: ctx.queue.value,
      activeTrack: ctx.activeTrack.value,
      history: ctx.history.value,
      playedTrackIds: context.playedTrackIds
    });

    // Reaching a hundred playable tracks usually overshoots -- pages arrive a
    // hundred at a time, so the detail page holds more than the queue was
    // seeded with. Those are already in hand and would otherwise be skipped
    // over entirely, since the walk below only ever considers tracks that are
    // new to `allTracks`.
    absorb(unqueued());

    if (!context.hasMoreTracks) {
      ctx.clearNextPreload();
      void nextTick(() => ctx.preloadNextTrack());
      return;
    }

    ctx.playlistBackfill.value = {
      active: true,
      loaded: context.allTracks.length,
      total: Number(ctx.activeTrack.value?.queueOrigin?.totalTrackCount) || 0,
      browseId
    };

    try {
      while (context.hasMoreTracks && context.continuation) {
        const continuation = context.continuation;
        const data = await ctx.emitWithReply('music:playlist:more', {
          browseId,
          continuation,
          startIndex: context.allTracks.length
        });

        if (stale()) return;
        if (!data) break;

        context.continuation = data.continuation || '';
        context.hasMoreTracks = Boolean(data.hasMoreTracks && data.continuation && data.continuation !== continuation);

        const seenIds = new Set(context.allTracks.map((track) => track.id).filter(Boolean));
        const newTracks = (data.tracks || [])
          .map((track) => ctx.trackWithCollectionContext(track, { browseId, kind: 'playlist' }))
          .filter((track) => ctx.isPlayableTrack(track) && track.id && !seenIds.has(track.id));

        if (!newTracks.length && !context.hasMoreTracks) break;
        context.allTracks.push(...newTracks);

        // Anything already played, queued, or playing is not a new arrival --
        // the first page is in the queue before this walk ever starts.
        const queuedIds = new Set([
          ...ctx.queue.value,
          ctx.activeTrack.value,
          ...ctx.history.value,
          ...(context.playedTrackIds || [])
        ].map((track) => track?.id || track).filter(Boolean));
        const arrivals = newTracks.filter((track) => !queuedIds.has(track.id));

        if (arrivals.length) {
          ctx.queue.value = mergeShuffledTail(ctx.queue.value, arrivals, SHUFFLE_PINNED_LOOKAHEAD, ctx.shuffleItems);
          // Un-shuffle restores playlist order, so the source list grows in the
          // order the pages arrived rather than the order the queue holds them.
          if (ctx.shuffleEnabled.value) {
            ctx.shuffleSourceQueue.value = [...ctx.shuffleSourceQueue.value, ...arrivals];
          }
        }

        ctx.playlistBackfill.value = {
          ...ctx.playlistBackfill.value,
          loaded: context.allTracks.length
        };
      }
    } catch (error) {
      console.error('Failed to load the rest of the playlist for shuffle:', error);
    } finally {
      if (!stale()) {
        ctx.playlistBackfill.value = { ...ctx.playlistBackfill.value, active: false };
        ctx.clearNextPreload();
        void nextTick(() => ctx.preloadNextTrack());
      }
    }
  };

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
    // The background shuffle walk owns `context.continuation` while it runs.
    // Two loops advancing the same chain would skip pages in both.
    if (ctx.playlistBackfill.value.active) return;

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
