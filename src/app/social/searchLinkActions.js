import { nextTick } from 'vue';

const songLinksOrigin = 'https://songlinks.sfg545.dev';
const youtubeHosts = new Set([
  'youtu.be',
  'www.youtube.com',
  'youtube.com',
  'm.youtube.com',
  'music.youtube.com',
  'www.youtube-nocookie.com',
  'youtube-nocookie.com'
]);
const orchardLinkHosts = new Set(['songlinks.sfg545.dev']);

function cleanText(value = '') {
  return String(value || '').trim();
}

function videoId(value = '') {
  const text = cleanText(value);
  return /^[a-zA-Z0-9_-]{11}$/.test(text) ? text : '';
}

function maybeUrl(value = '') {
  const text = cleanText(value);
  if (!text) return null;

  const candidate = /^[a-z][a-z0-9+.-]*:/i.test(text)
    ? text
    : /^(?:www\.youtube\.com|youtube\.com|m\.youtube\.com|music\.youtube\.com|youtu\.be|songlinks\.sfg545\.dev)\b/i.test(text)
      ? `https://${text}`
      : text;

  try {
    return new URL(candidate);
  } catch {
    return null;
  }
}

function collectionKindFromBrowseId(browseId = '') {
  if (browseId.startsWith('MPSP')) return 'podcast';
  if (browseId.startsWith('MPRE') || browseId.startsWith('MPR')) return 'album';
  if (['VL', 'PL', 'OLAK', 'RD'].some((prefix) => browseId.startsWith(prefix))) return 'playlist';
  return 'artist';
}

function parseYoutubeLink(url) {
  const host = url.hostname.replace(/^www\./, 'www.');
  if (!youtubeHosts.has(host) && !youtubeHosts.has(url.hostname)) return null;

  if (url.hostname === 'youtu.be') {
    const id = videoId(url.pathname.split('/').filter(Boolean)[0]);
    return id ? { type: 'video', videoId: id } : null;
  }

  const watchId = videoId(url.searchParams.get('v'));
  if (watchId) return { type: 'video', videoId: watchId };

  const embeddedId = videoId(url.pathname.match(/^\/(?:shorts|embed|live)\/([^/?#]+)/)?.[1]);
  if (embeddedId) return { type: 'video', videoId: embeddedId };

  const playlistId = cleanText(url.searchParams.get('list'));
  if (playlistId) {
    return {
      type: 'collection',
      kind: playlistId.startsWith('OLAK') ? 'album' : 'playlist',
      fallbackKinds: playlistId.startsWith('OLAK') ? ['playlist'] : [],
      browseId: playlistId
    };
  }

  const browseId = cleanText(url.pathname.match(/^\/(?:browse|channel)\/([^/?#]+)/)?.[1]);
  if (browseId) {
    return {
      type: 'collection',
      kind: collectionKindFromBrowseId(browseId),
      browseId
    };
  }

  return null;
}

function parseOrchardProtocolLink(url) {
  if (url.protocol !== 'orchard:') return null;

  const kind = cleanText(url.hostname).toLowerCase();
  const id = cleanText(url.pathname.split('/').filter(Boolean)[0]);
  if (!kind || !id) return null;

  if (['video', 'watch', 'track', 'song'].includes(kind) && videoId(id)) {
    return { type: 'video', videoId: id };
  }

  if (kind === 's' || kind === 'songlink') return { type: 'orchard-song', id };
  if (kind === 'c' || kind === 'share' || kind === 'collection') return { type: 'orchard-collection', id };
  if (['album', 'artist', 'playlist', 'podcast'].includes(kind)) {
    return { type: 'collection', kind, browseId: id };
  }

  return null;
}

function parseOrchardWebLink(url) {
  if (orchardLinkHosts.has(url.hostname) || /^localhost$|^127\.0\.0\.1$/.test(url.hostname)) {
    const songId = cleanText(url.pathname.match(/^\/(?:s|api\/songs)\/([^/?#]+)/)?.[1]);
    if (songId) return { type: 'orchard-song', id: songId, origin: url.origin };

    const collectionId = cleanText(url.pathname.match(/^\/(?:c|api\/collections)\/([^/?#]+)/)?.[1]);
    if (collectionId) return { type: 'orchard-collection', id: collectionId, origin: url.origin };
  }

  const internalTrackId = videoId(url.pathname.match(/\/dev\/sfg\/orchard\/track\/([^/?#]+)/)?.[1]);
  return internalTrackId ? { type: 'video', videoId: internalTrackId } : null;
}

function parseSearchLink(value = '') {
  const url = maybeUrl(value);
  if (!url) return null;

  return parseOrchardProtocolLink(url) ||
    parseOrchardWebLink(url) ||
    parseYoutubeLink(url);
}

export function installSearchLinkActions(ctx) {
  ctx.searchLinkCandidate = function searchLinkCandidate(value = ctx.query.value) {
    return Boolean(parseSearchLink(value));
  };

  ctx.playLinkedVideo = async function playLinkedVideo(id) {
    await ctx.playTrack({
      id,
      type: 'video',
      mediaKind: 'video',
      thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`
    }, {
      mediaKind: 'video',
      preserveQueue: true
    });
  };

  ctx.openLinkedCollection = async function openLinkedCollection(link) {
    const previousEntry = ctx.createNavigationEntry();
    const previousView = ctx.activeView.value;
    const previousDetail = ctx.browseDetail.value;
    const previousSectionMore = ctx.sectionMoreDetail.value;
    const kinds = [...new Set([link.kind, ...(link.fallbackKinds || [])].filter(Boolean))];
    let lastError = null;

    for (const kind of kinds) {
      ctx.resetBrowseTrackPaging();
      ctx.browseLoading.value = true;
      ctx.errorMessage.value = '';
      ctx.warningMessage.value = '';
      ctx.browseDetail.value = null;
      ctx.sectionMoreDetail.value = null;
      ctx.activeView.value = 'browse';
      await nextTick();

      try {
        const data = await ctx.emitWithReply(`music:${kind}`, { browseId: link.browseId });
        ctx.pushNavigationEntry(previousEntry);
        ctx.browseOrigin.value = previousView === 'browse' ? ctx.browseOrigin.value : previousView;
        ctx.browseDetail.value = {
          ...data,
          title: data.title || link.title || kind[0].toUpperCase() + kind.slice(1),
          thumbnail: data.thumbnail || null,
          kind: data.kind || kind
        };
        await nextTick();
        void ctx.prefetchBrowseTrackPages();
        ctx.writeLastPageEntry();
        ctx.browseLoading.value = false;
        return;
      } catch (error) {
        lastError = error;
        ctx.activeView.value = previousView;
        ctx.browseDetail.value = previousDetail;
        ctx.sectionMoreDetail.value = previousSectionMore;
      } finally {
        ctx.browseLoading.value = false;
      }
    }

    throw lastError || new Error('Could not open that YouTube link.');
  };

  ctx.openOrchardSongLink = async function openOrchardSongLink(link) {
    const response = await fetch(new URL(`/api/songs/${encodeURIComponent(link.id)}`, link.origin || songLinksOrigin));
    if (!response.ok) throw new Error(`Orchard link could not be opened (${response.status}).`);

    const data = await response.json();
    const id = videoId(data?.song?.youtubeVideoId) ||
      videoId((data?.links || []).find((item) => /youtube/i.test(item?.platform || ''))?.url?.match(/[?&]v=([^&#]+)/)?.[1]);
    if (id) {
      await ctx.playLinkedVideo(id);
      return;
    }

    throw new Error('That Orchard link does not include a YouTube track.');
  };

  ctx.openOrchardCollectionLink = async function openOrchardCollectionLink(link) {
    const response = await fetch(new URL(`/api/collections/${encodeURIComponent(link.id)}`, link.origin || songLinksOrigin));
    if (!response.ok) throw new Error(`Orchard collection link could not be opened (${response.status}).`);

    const data = await response.json();
    const collection = data?.collection || {};
    if (!data?.ok || !collection?.kind) throw new Error('That Orchard collection link is incomplete.');

    if (!collection.orchardOnly && collection.browseId) {
      await ctx.openLinkedCollection({
        type: 'collection',
        kind: collection.kind,
        browseId: collection.browseId,
        title: collection.title
      });
      return;
    }

    const tracks = (Array.isArray(data.tracks) ? data.tracks : [])
      .map((track, index) => ({
        id: videoId(track.youtubeVideoId) || cleanText(track.youtubeVideoId),
        index: track.index || index + 1,
        title: cleanText(track.title),
        artist: cleanText(track.artist),
        artists: cleanText(track.artist) ? [cleanText(track.artist)] : [],
        album: cleanText(track.album),
        duration: track.duration || '',
        durationSeconds: Number(track.durationSeconds) || 0,
        thumbnail: cleanText(track.thumbnailUrl),
        type: 'track',
        mediaKind: 'audio'
      }))
      .filter((track) => track.title && track.id);

    const previousEntry = ctx.createNavigationEntry();
    ctx.pushNavigationEntry(previousEntry);
    ctx.browseOrigin.value = ctx.activeView.value === 'browse' ? ctx.browseOrigin.value : ctx.activeView.value;
    ctx.browseDetail.value = {
      kind: collection.kind || 'playlist',
      title: collection.title || 'Shared playlist',
      subtitle: collection.subtitle || 'Shared with Orchard',
      description: collection.orchardOnly ? 'Shared as an Orchard-only playlist snapshot.' : '',
      thumbnail: collection.thumbnailUrl || tracks[0]?.thumbnail || null,
      browseId: collection.browseId || `orchard:${link.id}`,
      itemCount: collection.itemCount || `${tracks.length} tracks`,
      tracks,
      orchardOnly: Boolean(collection.orchardOnly),
      editable: false
    };
    ctx.sectionMoreDetail.value = null;
    ctx.activeView.value = 'browse';
    await nextTick();
    ctx.writeLastPageEntry();
  };

  ctx.handleSearchLink = async function handleSearchLink(value = ctx.query.value) {
    const link = parseSearchLink(value);
    if (!link) return false;

    ctx.searchRequest += 1;
    ctx.loading.value = true;
    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';

    try {
      if (link.type === 'video') await ctx.playLinkedVideo(link.videoId);
      else if (link.type === 'collection') await ctx.openLinkedCollection(link);
      else if (link.type === 'orchard-song') await ctx.openOrchardSongLink(link);
      else if (link.type === 'orchard-collection') await ctx.openOrchardCollectionLink(link);
      ctx.query.value = '';
    } catch (error) {
      ctx.errorMessage.value = error.message || 'Could not open that link.';
    } finally {
      ctx.loading.value = false;
    }

    return true;
  };
}
