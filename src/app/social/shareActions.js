import { ref } from 'vue';

const songLinksOrigin = 'https://songlinks.sfg545.dev';

function cleanShareText(value = '') {
  return String(value || '').replace(/\s+/g, ' ').trim();
}

function durationSecondsFromTrack(track, fallback = 0) {
  const value = Number(track?.durationSeconds || fallback);
  return Number.isFinite(value) && value > 0 ? Math.round(value) : 0;
}

function stopContextMenu(event) {
  event?.preventDefault?.();
  event?.stopPropagation?.();
}

const songSharePlatformLogos = {
  youtube_music: 'https://cdn.simpleicons.org/youtubemusic/FF0000',
  youtube: 'https://cdn.simpleicons.org/youtube/FF0000',
  apple_music: 'https://cdn.simpleicons.org/applemusic/FA243C',
  spotify: 'https://cdn.simpleicons.org/spotify/1ED760',
  tidal: 'https://cdn.simpleicons.org/tidal/000000',
  deezer: 'https://cdn.simpleicons.org/deezer/A238FF'
};

function trackIsrc(track) {
  return cleanShareText(track?.isrc || track?.externalIds?.isrc || track?.external_ids?.isrc).toUpperCase();
}

function collectionKindLabel(kind = 'collection') {
  if (kind === 'artist') return 'artist';
  if (kind === 'album') return 'album';
  if (kind === 'playlist') return 'playlist';
  if (kind === 'podcast') return 'podcast';
  return 'collection';
}

function absoluteSongLinksUrl(value = '') {
  try {
    return new URL(value, songLinksOrigin).toString();
  } catch {
    return '';
  }
}

async function fetchSongLinksJson(path, payload) {
  const response = await fetch(new URL(path, songLinksOrigin), {
    method: 'POST',
    headers: {
      accept: 'application/json',
      'content-type': 'application/json'
    },
    body: JSON.stringify(payload || {})
  });

  const data = await response.json().catch(() => ({}));
  if (!response.ok || !data?.ok) {
    throw new Error(data?.error || `Song links returned ${response.status}.`);
  }

  return data;
}

async function copyShareText(value) {
  const text = cleanShareText(value);
  if (!text) return false;
  if (window.orchardClipboard?.writeText) {
    await window.orchardClipboard.writeText(text);
    return true;
  }
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return true;
  }
  throw new Error('Clipboard access is unavailable.');
}

export function installShareActions(ctx) {
  const openTrackArtistWithBrowseId = ctx.openTrackArtist;
  ctx.songShareDialog = ref({
    open: false,
    loading: false,
    error: '',
    label: '',
    payload: null,
    song: null,
    collection: null,
    shareUrl: '',
    links: []
  });
  ctx.songShareDialogRequest = 0;

  ctx.showShareMessage = function showShareMessage(message, isError = false) {
    window.clearTimeout(ctx.shareMessageTimer);
    if (isError) ctx.errorMessage.value = message;
    else ctx.warningMessage.value = message;

    ctx.shareMessageTimer = window.setTimeout(() => {
      if (isError && ctx.errorMessage.value === message) ctx.errorMessage.value = '';
      if (!isError && ctx.warningMessage.value === message) ctx.warningMessage.value = '';
    }, 3200);
  };

  ctx.songLinkPayloadForTrack = function songLinkPayloadForTrack(track, detail = null) {
    if (!track?.title) return null;

    const artist = cleanShareText(
      ctx.trackArtistLabel?.(track, detail) ||
      track.artist ||
      track.artists?.join(', ') ||
      (detail?.kind === 'artist' ? detail.artist || detail.title : '') ||
      ctx.activeArtist.value
    );
    if (!artist) return null;

    return {
      title: cleanShareText(track.title),
      artist,
      album: cleanShareText(track.album || (detail?.kind === 'album' ? detail.title : '')),
      isrc: trackIsrc(track),
      youtubeVideoId: cleanShareText(track.id),
      durationSeconds: durationSecondsFromTrack(track, track.id === ctx.activeTrack.value?.id ? ctx.duration.value : 0),
      thumbnailUrl: cleanShareText(
        track.id === ctx.activeTrack.value?.id
          ? ctx.nowArtworkImage.value || track.thumbnail
          : ctx.collectionTrackCover?.(track, detail) || track.thumbnail
      )
    };
  };

  ctx.resolveSongLink = async function resolveSongLink(payload) {
    if (!payload?.title || !payload?.artist) {
      throw new Error('Song links need a song title and artist.');
    }

    const link = await window.orchardSongLinks?.resolve?.(payload);
    if (!link) throw new Error('Could not resolve a song link.');
    return link;
  };

  ctx.resolveSongLinkDetails = async function resolveSongLinkDetails(payload) {
    if (!payload?.title || !payload?.artist) {
      throw new Error('Song links need a song title and artist.');
    }

    const details = await window.orchardSongLinks?.resolveDetails?.(payload);
    if (details?.shareUrl) return details;

    const shareUrl = await ctx.resolveSongLink(payload);
    return {
      ok: Boolean(shareUrl),
      shareUrl,
      song: {
        title: payload.title,
        artist: payload.artist,
        album: payload.album,
        isrc: payload.isrc,
        youtubeVideoId: payload.youtubeVideoId,
        durationSeconds: payload.durationSeconds,
        thumbnailUrl: payload.thumbnailUrl
      },
      links: []
    };
  };

  ctx.copySongLink = async function copySongLink(payload) {
    const link = await ctx.resolveSongLink(payload);
    await copyShareText(link);
    return link;
  };

  ctx.shareSongLinkPayload = async function shareSongLinkPayload(payload, label = '') {
    await ctx.openSongShareDialog(payload, label);
  };

  ctx.openSongShareDialog = async function openSongShareDialog(payload, label = '') {
    const requestId = ++ctx.songShareDialogRequest;
    ctx.songShareDialog.value = {
      open: true,
      loading: true,
      error: '',
      label,
      payload,
      song: payload,
      collection: null,
      shareUrl: '',
      links: []
    };

    try {
      const details = await ctx.resolveSongLinkDetails(payload);
      if (requestId !== ctx.songShareDialogRequest) return;

      ctx.songShareDialog.value = {
        open: true,
        loading: false,
        error: '',
        label,
        payload,
        song: details.song || payload,
        collection: null,
        shareUrl: details.shareUrl || '',
        links: Array.isArray(details.links) ? details.links : []
      };
    } catch (error) {
      if (requestId !== ctx.songShareDialogRequest) return;

      ctx.songShareDialog.value = {
        ...ctx.songShareDialog.value,
        loading: false,
        error: error.message || 'Could not resolve song links.'
      };
    }
  };

  ctx.closeSongShareDialog = function closeSongShareDialog() {
    ctx.songShareDialog.value = { ...ctx.songShareDialog.value, open: false };
  };

  ctx.copySongShareUrl = async function copySongShareUrl() {
    const url = ctx.songShareDialog.value.shareUrl;
    if (!url) return;
    try {
      await copyShareText(url);
      ctx.showShareMessage(`Copied Orchard ${ctx.songShareDialog.value.collection ? 'collection' : 'song'} link.`);
    } catch (error) {
      ctx.showShareMessage(error.message || 'Could not copy link.', true);
    }
  };

  ctx.openSongShareUrl = function openSongShareUrl(url) {
    const target = cleanShareText(url);
    if (!target) return;
    window.open(target, '_blank', 'noopener,noreferrer');
  };

  ctx.nativeShareSongUrl = async function nativeShareSongUrl() {
    const dialog = ctx.songShareDialog.value;
    if (!navigator.share || !dialog.shareUrl) return;

    try {
      await navigator.share({
        title: dialog.collection?.title || dialog.song?.title || dialog.payload?.title || 'Orchard link',
        text: dialog.collection
          ? [dialog.collection.title, dialog.collection.subtitle].filter(Boolean).join(' - ')
          : [dialog.song?.title || dialog.payload?.title, dialog.song?.artist || dialog.payload?.artist].filter(Boolean).join(' - '),
        url: dialog.shareUrl
      });
    } catch (error) {
      if (error.name !== 'AbortError') ctx.showShareMessage(error.message || 'Could not share link.', true);
    }
  };

  ctx.songShareLinkKind = function songShareLinkKind(link) {
    if (link?.match_type === 'search') return 'Search fallback';
    if (link?.match_type === 'direct') return 'Direct link';
    return 'Matched link';
  };

  ctx.songShareActionText = function songShareActionText(link) {
    return link?.match_type === 'search' ? 'Search' : 'Open';
  };

  ctx.songSharePlatformLogoUrl = function songSharePlatformLogoUrl(link) {
    return songSharePlatformLogos[cleanShareText(link?.platform)];
  };

  ctx.songSharePlatformClass = function songSharePlatformClass(link) {
    return `song-share-dialog__service--${cleanShareText(link?.platform).replace(/[^a-z0-9_-]/gi, '').toLowerCase() || 'service'}`;
  };

  ctx.shareTrackSongLink = function shareTrackSongLink(track, event, detail = null) {
    stopContextMenu(event);
    const payload = ctx.songLinkPayloadForTrack(track, detail);
    const label = cleanShareText(track?.title);
    void ctx.shareSongLinkPayload(payload, label);
  };

  ctx.shareActiveTrackSongLink = function shareActiveTrackSongLink(event) {
    ctx.shareTrackSongLink(ctx.activeTrack.value, event, ctx.browseDetail.value);
  };

  ctx.shareMediaSongLink = function shareMediaSongLink(item, event, detail = null) {
    stopContextMenu(event);
    if (!ctx.isPlayableTrack(item)) {
      const kind = ctx.resolveBrowseKind?.(item) || detail?.kind || '';
      if (kind) {
        ctx.openCollectionActionMenu?.(item, event, []);
        return;
      }

      ctx.showShareMessage('Sharing is available for tracks and collections.', true);
      return;
    }

    ctx.shareTrackSongLink(item, event, detail);
  };

  ctx.collectionTrackSharePayload = function collectionTrackSharePayload(track, detail = null) {
    return {
      title: cleanShareText(track?.title),
      artist: cleanShareText(ctx.trackArtistLabel?.(track, detail) || track?.artist || track?.artists?.join(', ') || detail?.artist || ''),
      album: cleanShareText(track?.album || (detail?.kind === 'album' ? detail?.title : '')),
      youtubeVideoId: cleanShareText(track?.id),
      durationSeconds: durationSecondsFromTrack(track),
      thumbnailUrl: cleanShareText(ctx.collectionTrackCover?.(track, detail) || track?.thumbnail)
    };
  };

  ctx.collectionLinkPayloadForItem = function collectionLinkPayloadForItem(item, detail = null) {
    const source = detail || item;
    const kind = source?.kind || ctx.resolveBrowseKind?.(item);
    const browseId = cleanShareText(source?.browseId || item?.browsePayload?.browseId || ctx.itemBrowseId?.(item));
    const title = cleanShareText(source?.title || item?.title);
    if (!kind || !title) return null;

    const isEditablePlaylist = kind === 'playlist' && Boolean(source?.editable);
    const tracks = Array.isArray(source?.tracks)
      ? source.tracks
        .map((track) => ctx.collectionTrackSharePayload(track, source))
        .filter((track) => track.title && (track.artist || track.youtubeVideoId))
        .slice(0, 300)
      : [];

    return {
      kind,
      title,
      subtitle: cleanShareText(
        source?.artist ||
        source?.subtitle ||
        source?.description ||
        ctx.itemMeta?.(item) ||
        ''
      ),
      browseId,
      thumbnailUrl: cleanShareText(ctx.detailArtworkImage?.value && source === ctx.browseDetail.value ? ctx.detailArtworkImage.value : source?.thumbnail || item?.thumbnail),
      itemCount: cleanShareText(source?.itemCount || source?.totalDuration || source?.year || ''),
      orchardOnly: isEditablePlaylist,
      tracks
    };
  };

  ctx.resolveCollectionLinkDetails = async function resolveCollectionLinkDetails(payload) {
    if (!payload?.kind || !payload?.title) {
      throw new Error('Collection links need a title and type.');
    }

    const details = await fetchSongLinksJson('/collections/resolve', payload);
    return {
      ...details,
      shareUrl: absoluteSongLinksUrl(details.shareUrl)
    };
  };

  ctx.openCollectionShareDialog = async function openCollectionShareDialog(payload, label = '') {
    const requestId = ++ctx.songShareDialogRequest;
    const kind = collectionKindLabel(payload?.kind);
    ctx.songShareDialog.value = {
      open: true,
      loading: true,
      error: '',
      label,
      payload,
      song: null,
      collection: payload,
      shareUrl: '',
      links: []
    };

    try {
      const details = await ctx.resolveCollectionLinkDetails(payload);
      if (requestId !== ctx.songShareDialogRequest) return;

      ctx.songShareDialog.value = {
        open: true,
        loading: false,
        error: '',
        label,
        payload,
        song: null,
        collection: details.collection || payload,
        shareUrl: details.shareUrl || '',
        links: Array.isArray(details.links) ? details.links : []
      };
    } catch (error) {
      if (requestId !== ctx.songShareDialogRequest) return;

      ctx.songShareDialog.value = {
        ...ctx.songShareDialog.value,
        loading: false,
        error: error.message || `Could not create ${kind} link.`
      };
    }
  };

  ctx.shareCollectionLink = function shareCollectionLink(item, event = null, detail = null) {
    stopContextMenu(event);
    const payload = ctx.collectionLinkPayloadForItem(item, detail);
    if (!payload) {
      ctx.showShareMessage('Could not build a collection link.', true);
      return;
    }

    void ctx.openCollectionShareDialog(payload, payload.title);
  };

  ctx.shareBrowseDetailLink = function shareBrowseDetailLink(event) {
    if (!ctx.browseDetail.value) return;
    ctx.shareCollectionLink(ctx.browseDetail.value, event, ctx.browseDetail.value);
  };

  ctx.canOpenActiveTrackArtist = function canOpenActiveTrackArtist() {
    return Boolean(ctx.activeArtist.value || ctx.activeTrackArtistBrowseId?.());
  };

  ctx.openTrackArtist = function openTrackArtist() {
    if (ctx.activeTrackArtistBrowseId?.()) {
      openTrackArtistWithBrowseId?.();
      return;
    }

    const artist = cleanShareText(ctx.activeArtist.value);
    if (!artist || !ctx.socket.value?.connected) return;

    ctx.query.value = artist;
    ctx.selectedFilter.value = 'artists';
    void ctx.runSearch();
  };
}
