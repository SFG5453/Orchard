import { openCollectionWithLoading } from './collectionNavigation.js';

export function installBrowseActions(ctx) {
  ctx.cancelBrowseTrackPrefetch = function cancelBrowseTrackPrefetch() {
    ctx.browseTrackPrefetchRequest += 1;
    ctx.browseTrackPrefetching = false;
  };

  ctx.resetBrowseTrackPaging = function resetBrowseTrackPaging() {
    ctx.browseTrackPageRequest += 1;
    ctx.cancelBrowseTrackPrefetch();
    ctx.browseTrackPageLoading.value = false;
    ctx.browseTrackPageError.value = '';
  };

  ctx.loadMoreBrowseTracks = async function loadMoreBrowseTracks() {
    const detail = ctx.browseDetail.value;
    if (
      !ctx.socket.value?.connected ||
      ctx.activeView.value !== 'browse' ||
      !detail ||
      detail.kind !== 'playlist' ||
      !detail.continuation ||
      ctx.browseTrackPageLoading.value
    ) {
      return;
    }

    const requestId = ++ctx.browseTrackPageRequest;
    ctx.browseTrackPageLoading.value = true;
    ctx.browseTrackPageError.value = '';

    try {
      const data = await ctx.emitWithReply('music:playlist:more', {
        browseId: detail.browseId,
        continuation: detail.continuation,
        startIndex: detail.tracks?.length || 0
      });

      if (requestId !== ctx.browseTrackPageRequest || ctx.browseDetail.value?.browseId !== detail.browseId) return;

      const seen = new Set((ctx.browseDetail.value.tracks || []).map((track) => `${track.id || ''}:${track.index || ''}:${track.title || ''}`));
      const tracks = (data.tracks || []).filter((track) => {
        const key = `${track.id || ''}:${track.index || ''}:${track.title || ''}`;
        if (!key.trim() || seen.has(key)) return false;
        seen.add(key);
        return true;
      });

      ctx.browseDetail.value = {
        ...ctx.browseDetail.value,
        tracks: [...(ctx.browseDetail.value.tracks || []), ...tracks],
        continuation: data.continuation || '',
        hasMoreTracks: Boolean(data.hasMoreTracks && data.continuation && data.continuation !== detail.continuation)
      };
    } catch (error) {
      if (requestId === ctx.browseTrackPageRequest) {
        ctx.browseTrackPageError.value = error.message || 'Could not finish loading tracks.';
      }
    } finally {
      if (requestId === ctx.browseTrackPageRequest) {
        ctx.browseTrackPageLoading.value = false;
      }
    }
  };

  ctx.prefetchBrowseTrackPages = async function prefetchBrowseTrackPages() {
    if (
      ctx.browseTrackPrefetching ||
      ctx.activeView.value !== 'browse' ||
      ctx.browseDetail.value?.kind !== 'playlist' ||
      !ctx.browseDetail.value?.hasMoreTracks
    ) return;

    const browseId = ctx.browseDetail.value.browseId;
    const requestId = ++ctx.browseTrackPrefetchRequest;
    ctx.browseTrackPrefetching = true;

    try {
      while (
        requestId === ctx.browseTrackPrefetchRequest &&
        ctx.activeView.value === 'browse' &&
        ctx.browseDetail.value?.browseId === browseId &&
        ctx.browseDetail.value?.hasMoreTracks
      ) {
        const continuation = ctx.browseDetail.value.continuation;
        await ctx.loadMoreBrowseTracks();
        if (
          ctx.browseTrackPageError.value ||
          !ctx.browseDetail.value?.continuation ||
          ctx.browseDetail.value.continuation === continuation
        ) break;
      }
    } finally {
      if (requestId === ctx.browseTrackPrefetchRequest) ctx.browseTrackPrefetching = false;
    }
  };

  ctx.loadBrowseTracksUntil = async function loadBrowseTracksUntil(limit, detail = ctx.browseDetail.value) {
    const browseId = detail?.browseId;
    const getUniquePlayableCount = (d) => {
      if (!d?.tracks) return 0;
      const seen = new Set();
      let count = 0;
      for (const track of d.tracks) {
        const playableTrack = ctx.trackWithCollectionContext(track, d);
        if (ctx.isPlayableTrack(playableTrack) && playableTrack.id && !seen.has(playableTrack.id)) {
          seen.add(playableTrack.id);
          count++;
        }
      }
      return count;
    };

    while (
      browseId &&
      ctx.browseDetail.value?.browseId === browseId &&
      getUniquePlayableCount(ctx.browseDetail.value) < limit &&
      ctx.browseDetail.value.hasMoreTracks
    ) {
      const continuation = ctx.browseDetail.value.continuation;
      await ctx.loadMoreBrowseTracks();

      if (
        ctx.browseTrackPageError.value ||
        !ctx.browseDetail.value?.continuation ||
        ctx.browseDetail.value.continuation === continuation
      ) {
        break;
      }
    }

    return ctx.browseDetail.value?.browseId === browseId
      ? ctx.browseDetail.value
      : detail;
  };

  ctx.openCollection = (kind, item) => openCollectionWithLoading(ctx, kind, item);

  ctx.browseBack = function browseBack() {
    if (ctx.navigationHistory.value.length) ctx.goBack();
    else ctx.activeView.value = ctx.browseOrigin.value || 'home';
  };

  ctx.detailBack = function detailBack() {
    if (ctx.activeView.value === 'sectionMore') {
      if (ctx.navigationHistory.value.length) ctx.goBack();
      else {
        const originView = ctx.sectionMoreDetail.value?.originView || 'browse';
        ctx.sectionMoreDetail.value = null;
        ctx.activeView.value = originView;
      }
      return;
    }

    ctx.browseBack();
  };

  ctx.isExpandableShelfSection = function isExpandableShelfSection(section) {
    const key = section?.key || '';
    const title = section?.title || '';

    return /albums?|singles?|videos?/i.test(`${key} ${title}`);
  };

  ctx.sectionHasMore = function sectionHasMore(section) {
    return ctx.isExpandableShelfSection(section) && (section.browsePayload || section.items?.length >= ctx.SECTION_PREVIEW_LIMIT);
  };

  ctx.sectionPreviewItems = function sectionPreviewItems(section) {
    return section.items || [];
  };

  ctx.isVideoShelfSection = function isVideoShelfSection(section) {
    return /videos?/i.test(`${section?.key || ''} ${section?.title || ''}`);
  };

  ctx.setShelfRail = function setShelfRail(key, element) {
    if (element) ctx.shelfRails.set(key, element);
    else ctx.shelfRails.delete(key);
  };

  ctx.scrollShelf = function scrollShelf(target, direction) {
    const section = target?.currentTarget?.closest?.('.shelf-section, .playlist-showcase');
    const rail = typeof target === 'string'
      ? ctx.shelfRails.get(target)
      : section?.querySelector('.media-rail, .playlist-rail');
    if (!rail) return;

    const card = rail.querySelector('.media-card, .playlist-card');
    const gap = Number.parseFloat(getComputedStyle(rail).columnGap) || 14;
    const step = card
      ? Math.max(card.getBoundingClientRect().width + gap, rail.clientWidth * 0.72)
      : Math.max(rail.clientWidth * 0.72, 320);

    rail.scrollBy({
      left: direction * step,
      behavior: 'smooth'
    });
  };

  ctx.openSectionMore = async function openSectionMore(section) {
    if (!ctx.sectionHasMore(section)) return;
    const sourceDetail = ctx.browseDetail.value;
    ctx.pushNavigationEntry();
    ctx.sectionMoreDetail.value = {
      key: section.key, title: section.title, items: section.items || [], loading: false,
      sourceTitle: ctx.browseDetail.value?.title || ctx.pageTitle.value,
      originView: ctx.activeView.value
    };
    ctx.activeView.value = 'sectionMore';

    if (sourceDetail?.kind !== 'artist' || !section.browsePayload) return;
    ctx.sectionMoreDetail.value.loading = true;

    try {
      const data = await ctx.emitWithReply('music:artist:section', { browseId: sourceDetail.browseId, section });
      if (ctx.sectionMoreDetail.value?.key === section.key) {
        ctx.sectionMoreDetail.value = { ...ctx.sectionMoreDetail.value, ...data, loading: false };
      }
    } catch (error) {
      if (ctx.sectionMoreDetail.value?.key === section.key) ctx.sectionMoreDetail.value.loading = false;
      ctx.errorMessage.value = error.message;
    }
  };

  ctx.openVerification = function openVerification() {
    const url = ctx.authState.value.pending?.verificationUrl;
    if (url) window.open(url, '_blank', 'noopener,noreferrer');
  };

  ctx.copyLoginText = async function copyLoginText(value) {
    if (!value) return;

    try {
      await navigator.clipboard.writeText(value);
    } catch {
      ctx.errorMessage.value = `Copy failed. Use ${value}`;
    }
  };

  ctx.itemBrowseId = function itemBrowseId(item) {
    return item?.browsePayload?.browseId || item?.browseId || item?.albumId || item?.artistBrowseId || item?.artistBrowseIds?.[0] || null;
  };

  ctx.trackCover = function trackCover(item) {
    return item?.thumbnail || (item?.id ? `https://i.ytimg.com/vi/${item.id}/hqdefault.jpg` : '');
  };

  ctx.collectionTrackCover = function collectionTrackCover(track, detail) {
    return track?.thumbnail || (detail?.kind === 'album' ? detail.thumbnail : '') || '';
  };

  ctx.isArtistItem = function isArtistItem(item) {
    const pageType = item?.browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    const browseId = item?.browsePayload?.browseId || item?.browseId || '';
    return pageType === 'MUSIC_PAGE_TYPE_ARTIST' ||
      pageType === 'MUSIC_PAGE_TYPE_LIBRARY_ARTIST' ||
      item?.type === 'artist' ||
      item?.type === 'library_artist' ||
      browseId.startsWith('FEmusic_library_privately_owned_artist');
  };

  ctx.isAlbumItem = function isAlbumItem(item) {
    const pageType = item?.browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    const browseId = item?.browsePayload?.browseId || item?.browseId || '';
    return pageType === 'MUSIC_PAGE_TYPE_ALBUM' ||
      item?.type === 'album' ||
      Boolean(item?.albumId && !item?.id) ||
      browseId.startsWith('MPR') ||
      browseId.startsWith('FEmusic_library_privately_owned_release');
  };

  ctx.isPlaylistItem = function isPlaylistItem(item) {
    const pageType = item?.browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    const browseId = item?.browsePayload?.browseId || item?.browseId || '';
    return pageType === 'MUSIC_PAGE_TYPE_PLAYLIST' ||
      item?.type === 'playlist' ||
      browseId.startsWith('VL') ||
      browseId.startsWith('PL') ||
      browseId.startsWith('OLAK') ||
      browseId.startsWith('RD');
  };

  ctx.resolveBrowseKind = function resolveBrowseKind(item) {
    if (item?.type === 'future_album') return 'future-album';
    if (ctx.isPodcastItem?.(item)) return 'podcast';
    if (ctx.isAlbumItem(item)) return 'album';
    if (ctx.isArtistItem(item)) return 'artist';
    if (ctx.isPlaylistItem(item)) return 'playlist';
    return null;
  };

  ctx.activeQueueSource = function activeQueueSource() {
    if (ctx.activeView.value === 'browse' && ctx.browseDetail.value?.tracks?.length) {
      return ctx.browseDetail.value.tracks.filter(ctx.isPlayableTrack);
    }

    if (ctx.activeView.value === 'search') return ctx.flatPlayableResults.value;
    return ctx.flatPlayableHomeItems.value;
  };

  ctx.isPlayableTrack = function isPlayableTrack(item) {
    if (!item?.id || item.unplayable || ['event', 'future_track'].includes(item.type)) return false;
    return !ctx.isPlaylistItem(item) && !ctx.isAlbumItem(item) && !ctx.isArtistItem(item);
  };

  ctx.shouldPlayAsAudioTrack = function shouldPlayAsAudioTrack(item) {
    if (!ctx.isPlayableTrack(item) || ctx.shouldPlayAsVideo(item)) return false;
    return item.type === 'song' ||
      item.type === 'track' ||
      item.isAudioOnly ||
      item.musicVideoType === 'MUSIC_VIDEO_TYPE_ATV';
  };

  ctx.openMedia = function openMedia(item, source = []) {
    if (!item) return;

    if (item.type === 'event' && item.externalUrl) {
      return window.open(item.externalUrl, '_blank', 'noopener,noreferrer');
    }

    if (ctx.isPlayableTrack(item) && ctx.shouldPlayAsVideo(item)) {
      return ctx.playTrack(item, { queueSource: source, mediaKind: 'video' });
    }

    if (ctx.shouldPlayAsAudioTrack(item)) {
      return ctx.playTrack(item, { queueSource: source, mediaKind: 'audio' });
    }

    const kind = ctx.resolveBrowseKind(item);
    if (kind) {
      return ctx.openCollection(kind, item);
    }

    if (item.externalUrl) {
      return window.open(item.externalUrl, '_blank', 'noopener,noreferrer');
    }

    if (ctx.isPlayableTrack(item)) {
      return ctx.playTrack(item, { queueSource: source });
    }

    if (item.title) {
      ctx.query.value = item.title;
      return ctx.runSearch();
    }
  };

  ctx.openTrackAlbum = function openTrackAlbum() {
    if (!ctx.activeTrack.value?.albumId && ctx.activeTrack.value?.futureAlbumId) {
      ctx.openCollection('future-album', {
        ...ctx.activeTrack.value,
        browseId: `itunes:${ctx.activeTrack.value.futureAlbumId}`,
        title: ctx.activeTrack.value.album || ctx.activeTrack.value.title
      });
      return;
    }

    if (!ctx.activeTrack.value?.albumId) return;
    ctx.openCollection('album', {
      ...ctx.activeTrack.value,
      browseId: ctx.activeTrack.value.albumId
    });
  };

  ctx.trackAlbumLabel = function trackAlbumLabel(track) {
    return track?.album || '—';
  };

  ctx.isCollectionMetaText = function isCollectionMetaText(value = '') {
    return /\b(playlist|album|single|ep)\b\s*•/i.test(String(value || '')) ||
      /\b\d+\s+(tracks?|songs?|videos?)\b/i.test(String(value || ''));
  };

  ctx.trackArtistLabel = function trackArtistLabel(track, detail = null) {
    const artist = track?.artist || track?.artists?.join(', ') || '';
    if (artist && !ctx.isCollectionMetaText(artist)) return artist;
    if (detail?.kind === 'artist' && detail.artist && !ctx.isCollectionMetaText(detail.artist)) return detail.artist;
    return '';
  };

  ctx.findArtistByName = async function findArtistByName(name) {
    const query = String(name || '').trim();
    if (!query || !ctx.socket.value?.connected) return null;

    try {
      const result = await ctx.emitWithReply('music:search', { query, filter: 'artists' });
      const items = (result.sections || [])
        .flatMap((section) => section.key === 'artists' ? section.items : []);
      const normalizedQuery = ctx.normalizedLookupText(query);
      return items.find((item) => ctx.normalizedLookupText(item.title) === normalizedQuery) || items[0] || null;
    } catch {
      return null;
    }
  };

  ctx.canOpenBrowseTrackAlbum = function canOpenBrowseTrackAlbum(track) {
    return Boolean(track?.albumId || track?.futureAlbumId);
  };

  ctx.openBrowseTrackAlbum = function openBrowseTrackAlbum(track) {
    if (!track?.albumId) {
      if (track?.futureAlbumId) {
        ctx.openCollection('future-album', {
          ...track,
          browseId: `itunes:${track.futureAlbumId}`,
          title: track.album || track.title,
          artist: track.artist || track.artists?.join(', ') || ctx.browseDetail.value?.artist || ''
        });
      }
      return;
    }

    ctx.openCollection('album', {
      ...track,
      browseId: track.albumId,
      title: track.album || track.title,
      artist: track.artist || track.artists?.join(', ') || ctx.browseDetail.value?.artist || ''
    });
  };

  ctx.trackArtistLinks = function trackArtistLinks(track, detail = null) {
    const names = (track?.artists?.length ? track.artists : [ctx.trackArtistLabel(track, detail)])
      .map((artist) => String(artist || '').trim())
      .filter((artist) => artist && !ctx.isCollectionMetaText(artist));
    const browseIds = Array.isArray(track?.artistBrowseIds) ? track.artistBrowseIds : [];

    return names.map((name, index) => ({
      name,
      browseId: browseIds[index] || (index === 0 ? track?.artistBrowseId : '') || ''
    }));
  };

  ctx.openBrowseTrackArtist = async function openBrowseTrackArtist(track, artist) {
    const title = artist?.name || ctx.trackArtistLabel(track, ctx.browseDetail.value) || 'Artist';
    const fallback = artist?.browseId || track?.artistBrowseId || track?.artistBrowseIds?.[0]
      ? null
      : await ctx.findArtistByName(title);
    const browseId = artist?.browseId || track?.artistBrowseId || track?.artistBrowseIds?.[0] || ctx.itemBrowseId(fallback) || '';
    if (!browseId) return;

    ctx.openCollection('artist', {
      ...track,
      browseId,
      title: fallback?.title || title
    });
  };

  ctx.openBrowseDetailArtist = async function openBrowseDetailArtist(detail = ctx.browseDetail.value) {
    const title = detail?.artist || detail?.subtitle || 'Artist';
    const fallback = detail?.artistBrowseId || detail?.artistBrowseIds?.[0]
      ? null
      : await ctx.findArtistByName(title);
    const browseId = detail?.artistBrowseId || detail?.artistBrowseIds?.[0] || ctx.itemBrowseId(fallback) || '';
    if (!browseId) return;

    ctx.openCollection('artist', {
      browseId,
      title: fallback?.title || title
    });
  };

  ctx.playBrowseDetailTrack = function playBrowseDetailTrack(track) {
    if (!ctx.isPlayableTrack(track)) return;

    const options = {
      queueSource: ctx.tracksWithCollectionContext(ctx.browseDetail.value)
    };
    if (['album', 'podcast'].includes(ctx.browseDetail.value?.kind)) options.mediaKind = 'audio';

    ctx.playTrack(ctx.trackWithCollectionContext(track, ctx.browseDetail.value), {
      ...options,
      resetHistory: ctx.browseDetail.value?.kind === 'playlist'
    });
  };

  ctx.onBrowseTrackRowKeydown = function onBrowseTrackRowKeydown(event, track) {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    ctx.playBrowseDetailTrack(track);
  };

  ctx.activeTrackArtistBrowseId = function activeTrackArtistBrowseId() {
    return ctx.activeTrack.value?.artistBrowseId ||
      ctx.activeTrack.value?.artistBrowseIds?.[0] ||
      null;
  };

  ctx.openTrackArtist = function openTrackArtist() {
    const browseId = ctx.activeTrackArtistBrowseId();
    if (!browseId) return;

    ctx.openCollection('artist', {
      ...ctx.activeTrack.value,
      browseId,
      title: ctx.activeArtist.value || ctx.activeTrack.value?.artist || 'Artist'
    });
  };
}
