// Composes item-level normalization into sections, playlists, albums, and continuations.
import { releaseTypeFromText } from './musicItemTypes.js';
import { playlistItemCount, playlistTotalItemCount } from './playlistCount.js';
import { createBrowseItemNormalizers } from './browseItemNormalizers.js';
export function createBrowseNormalizers({
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  hasExplicitBadge,
  normalizeTrack,
  normalizedLooseText,
  textParts
}) {
  const {
    normalizeRawBrowseItem,
    normalizeRawResponsiveListItem
  } = createBrowseItemNormalizers({
    asText,
    bestThumbnail,
    findDurationText,
    hasExplicitBadge,
    textParts
  });

  function browseHeaderArtist(header) {
    return cleanedText(header?.author?.name || header?.strapline_text_one) ||
      header?.subtitle?.runs?.find((run) => run?.endpoint?.payload?.browseId?.startsWith?.('UC'))?.text ||
      '';
  }
  function browseHeaderTitle(header) {
    return cleanedText(header?.title || header?.header?.title) || '';
  }
  function browseHeaderDescription(header) {
    return cleanedText(header?.description?.text || header?.description || header?.header?.description || header?.subtitle || header?.header?.subtitle) || '';
  }
  function browseHeaderThumbnail(header) {
    return header?.thumbnail || header?.thumbnails || header?.foreground_thumbnail || header?.background || header?.header?.thumbnail || null;
  }
  function browseHeaderYear(header) {
    return header?.year || textParts(header?.subtitle).find((part) => (/^[12][0-9]{3}$/).test(part)) || '';
  }
  function browseHeaderItemCount(header) {
    return textParts(header?.second_subtitle).find((part) => /\b(song|songs|track|tracks|video|videos)\b/i.test(part)) || '';
  }
  function browseHeaderDuration(header) {
    return textParts(header?.second_subtitle).find((part) => /\b(hour|hours|minute|minutes)\b/i.test(part)) || '';
  }
  function browseHeaderViews(header) {
    return textParts(header?.second_subtitle).find((part) => /\b(view|views|play|plays)\b/i.test(part)) || '';
  }
  function normalizeBrowseTrack(item, index = 0) {
    const normalized = normalizeTrack(item);
    return {
      ...normalized,
      index: asText(item.index) || String(index + 1),
      views: normalized.views || asText(item.flex_columns?.[2]?.title) || ''
    };
  }

  function rawMicroformat(data) {
    return data?.microformat?.microformatDataRenderer || {};
  }
  function rawHeader(data) {
    return data?.header?.musicEditablePlaylistDetailHeaderRenderer ||
      data?.header?.musicResponsiveHeaderRenderer ||
      data?.header?.musicImmersiveHeaderRenderer ||
      data?.header?.musicDetailHeaderRenderer ||
      data?.header?.musicVisualHeaderRenderer ||
      data?.header?.musicHeaderRenderer ||
      data?.header ||
      {};
  }
  function rawSectionList(data) {
    return data?.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents ||
      data?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents ||
      [];
  }
  function rawSectionNode(section) {
    return section?.musicShelfRenderer ||
      section?.musicPlaylistShelfRenderer ||
      section?.musicCarouselShelfRenderer ||
      section?.gridRenderer ||
      null;
  }

  function rawSectionTitle(node) {
    return asText(
      node?.title ||
      node?.header?.musicCarouselShelfBasicHeaderRenderer?.title ||
      node?.header?.musicSideAlignedItemRenderer?.title ||
      node?.header?.musicShelfHeaderRenderer?.title ||
      node?.header?.title
    ) || 'Library';
  }

  function rawSectionMoreBrowsePayload(node) {
    const header = node?.header?.musicCarouselShelfBasicHeaderRenderer ||
      node?.header?.musicShelfHeaderRenderer ||
      node?.header ||
      {};
    const button = header?.moreContentButton?.buttonRenderer ||
      header?.moreContentButton?.button ||
      node?.moreContentButton?.buttonRenderer ||
      null;
    const browse = button?.navigationEndpoint?.browseEndpoint ||
      button?.endpoint?.payload ||
      null;

    return browse?.browseId ? { ...browse } : null;
  }

  function isExpandableBrowseSectionTitle(title = '') {
    return /albums?|singles?|eps?|videos?/i.test(title);
  }

  function rawBrowseTitle(data, kind) {
    const header = rawHeader(data);
    const mfTitle = asText(rawMicroformat(data).title);

    if (kind === 'artist') return asText(header.title) || mfTitle || 'Artist';
    if (kind === 'album') return (mfTitle.split(' - Album by ')[0] || asText(header.title) || 'Album').trim();
    return mfTitle || asText(header.title) || 'Playlist';
  }

  function rawBrowseArtistName(data) {
    const header = rawHeader(data);
    const mfTitle = asText(rawMicroformat(data).title);
    const artist = mfTitle.includes(' - Album by ') ? mfTitle.split(' - Album by ').pop() : '';
    return asText(header.title) || artist || '';
  }

  function rawBrowseDescription(data) {
    const mf = rawMicroformat(data);
    return cleanedText(mf.description || rawHeader(data).description || rawHeader(data).subtitle) || '';
  }

  function isYoutubeMusicDescription(description = '', title = '') {
    const normalizedDescription = normalizedLooseText(description);
    const normalizedTitle = normalizedLooseText(title);
    if (!normalizedDescription.startsWith('listen to ')) return false;
    if (!normalizedDescription.includes(' on youtube music')) return false;

    return !normalizedTitle ||
      normalizedDescription.startsWith(`listen to ${normalizedTitle}`);
  }

  function collectionDescription(data, title = '') {
    const description = rawBrowseDescription(data);
    return isYoutubeMusicDescription(description, title) ? '' : description;
  }

  function rawBrowseThumbnail(data) {
    return bestThumbnail(rawMicroformat(data).thumbnail || rawHeader(data).thumbnail || rawHeader(data).thumbnails || rawHeader(data).background || []);
  }

  function rawBrowseItemsFromEntries(entries = []) {
    return entries.flatMap((entry) => {
      const itemSection = entry?.itemSectionRenderer;
      if (itemSection) return rawBrowseItemsFromEntries(itemSection.contents || []);
      const node = rawSectionNode(entry);
      if (node) return node.contents || node.items || [];
      return [entry];
    });
  }

  function continuationActions(data) {
    return [
      ...(data?.onResponseReceivedActions || []),
      ...(data?.on_response_received_actions || []),
      ...(data?.on_response_received_endpoints || []),
      ...(data?.onResponseReceivedEndpoints || [])
    ];
  }

  function continuationActionItems(data) {
    return continuationActions(data).flatMap((action) => [
      ...(action?.appendContinuationItemsAction?.continuationItems || []),
      ...(action?.append_continuation_items_action?.continuation_items || []),
      ...(action?.reloadContinuationItemsCommand?.continuationItems || []),
      ...(action?.reload_continuation_items_command?.continuation_items || []),
      ...(action?.contents || [])
    ]);
  }

  function rawBrowseItemsFromData(data) {
    return [
      ...rawBrowseItemsFromEntries(rawSectionList(data)),
      ...rawBrowseItemsFromEntries(data?.continuationContents?.sectionListContinuation?.contents || []),
      ...(data?.continuationContents?.musicShelfContinuation?.contents || []),
      ...(data?.continuationContents?.gridContinuation?.items || []),
      ...(data?.continuation_contents?.contents || []),
      ...(data?.continuation_contents?.items || []),
      ...rawBrowseItemsFromEntries(continuationActionItems(data))
    ];
  }

  function browseContinuationTokenFromData(data) {
    return data?.continuationContents?.musicShelfContinuation?.continuation ||
      continuationTokenFromContinuations(data?.continuationContents?.musicShelfContinuation?.continuations) ||
      data?.continuationContents?.gridContinuation?.continuation ||
      continuationTokenFromContinuations(data?.continuationContents?.gridContinuation?.continuations) ||
      data?.continuationContents?.sectionListContinuation?.continuation ||
      continuationTokenFromContinuations(data?.continuationContents?.sectionListContinuation?.continuations) ||
      data?.continuation_contents?.continuation ||
      rawSectionList(data)
        .map(rawSectionNode)
        .map((node) => continuationTokenFromContinuations(node?.continuations))
        .find(Boolean) ||
      playlistContinuationTokenFromItems(rawBrowseItemsFromData(data)) ||
      null;
  }

  function normalizeBrowseSection(section, index) {
    const node = rawSectionNode(section);
    const items = node?.contents || node?.items || [];
    const normalizedItems = items
      .map(normalizeRawBrowseItem)
      .filter(Boolean);

    return {
      key: `${node?.type || 'section'}-${index}`,
      title: rawSectionTitle(node),
      items: normalizedItems,
      browsePayload: rawSectionMoreBrowsePayload(node)
    };
  }

  function normalizeAlbum(album, browseId) {
    const tracksSection = rawSectionList(album).find((section) => section.musicShelfRenderer)?.musicShelfRenderer;
    const tracks = (tracksSection?.contents || [])
      .map((item, index) => normalizeRawResponsiveListItem(item, index, rawBrowseArtistName(album)))
      .filter((item) => item.id);
    const artist = rawBrowseArtistName(album);
    const normalizedArtist = normalizedLooseText(artist);
    const artistBrowseId = tracks
      .map((track) => {
        const artistIndex = track.artists?.findIndex((name) => normalizedLooseText(name) === normalizedArtist) ?? -1;
        return artistIndex >= 0 ? track.artistBrowseIds?.[artistIndex] : '';
      })
      .find(Boolean) || tracks.find((track) => track.artistBrowseIds?.[0])?.artistBrowseIds?.[0] || '';
    const title = rawBrowseTitle(album, 'album');
    const description = collectionDescription(album, title);
    const explicit = hasExplicitBadge(rawHeader(album)) || tracks.some((track) => track.explicit);

    return {
      kind: 'album',
      browseId,
      title,
      subtitle: rawBrowseDescription(album),
      artist,
      artistBrowseId,
      releaseType: releaseTypeFromText(
        asText(rawHeader(album).subtitle),
        asText(rawHeader(album).second_subtitle),
        tracks.length ? `${tracks.length} tracks` : ''
      ),
      explicit,
      year: browseHeaderYear(rawHeader(album)),
      itemCount: tracks.length ? `${tracks.length} tracks` : '',
      totalDuration: '',
      views: '',
      description,
      thumbnail: rawBrowseThumbnail(album),
      tracks,
      sections: rawSectionList(album)
        .slice(1)
        .map(normalizeBrowseSection)
        .filter((section) => section.items.length > 0)
        .slice(0, 2)
    };
  }

  function playlistShelf(playlist) {
    return rawSectionList(playlist).find((section) => section.musicPlaylistShelfRenderer)?.musicPlaylistShelfRenderer || null;
  }

  function continuationTokenFromEndpoint(endpoint) {
    return endpoint?.continuationCommand?.token ||
      endpoint?.command?.continuationCommand?.token ||
      endpoint?.payload?.continuation ||
      null;
  }

  function continuationTokenFromContinuations(continuations = []) {
    return continuations
      .map((entry) =>
        entry?.nextContinuationData?.continuation ||
        entry?.reloadContinuationData?.continuation ||
        entry?.nextRadioContinuationData?.continuation
      )
      .find(Boolean) || null;
  }

  function playlistContinuationTokenFromItems(items = []) {
    for (const item of [...items].reverse()) {
      const renderer = item?.continuationItemRenderer;
      const token = renderer?.continuationEndpoint?.continuationCommand?.token ||
        continuationTokenFromEndpoint(renderer?.continuationEndpoint);
      if (token) return token;
    }

    return null;
  }

  function playlistContinuationTokenFromData(data) {
    const shelf = playlistShelf(data);
    return playlistContinuationTokenFromItems(shelf?.contents || []) ||
      continuationTokenFromContinuations(shelf?.continuations) ||
      continuationTokenFromContinuations(rawSectionList(data).find((section) => section.musicPlaylistShelfRenderer)?.musicPlaylistShelfRenderer?.continuations) ||
      data?.continuationContents?.musicPlaylistShelfContinuation?.continuation ||
      continuationTokenFromContinuations(data?.continuationContents?.musicPlaylistShelfContinuation?.continuations) ||
      playlistContinuationTokenFromItems(data?.continuationContents?.musicPlaylistShelfContinuation?.contents || []) ||
      data?.continuation_contents?.continuation ||
      playlistContinuationTokenFromItems(data?.continuation_contents?.contents || []) ||
      playlistContinuationTokenFromItems(continuationActionItems(data)) ||
      null;
  }

  function playlistItemsFromData(data) {
    return [
      ...(playlistShelf(data)?.contents || []),
      ...(data?.continuationContents?.musicPlaylistShelfContinuation?.contents || []),
      ...(data?.continuation_contents?.contents || []),
      ...continuationActionItems(data)
    ]
      .filter((item) => item?.musicResponsiveListItemRenderer);
  }

  function normalizePlaylistTracksFromData(data, startIndex = 0) {
    return playlistItemsFromData(data)
      .map((item, index) => normalizeRawResponsiveListItem(item, startIndex + index))
      .filter((item) => item.id);
  }

  function normalizePlaylistPage(data, startIndex = 0) {
    const tracks = normalizePlaylistTracksFromData(data, startIndex);
    const continuation = playlistContinuationTokenFromData(data);

    return {
      tracks,
      continuation,
      hasMoreTracks: Boolean(continuation)
    };
  }

  function normalizePlaylist(collection, authoritativeItemCount = '') {
    const playlist = collection.data;
    const browseId = collection.browseId;
    const page = normalizePlaylistPage(playlist);
    const title = rawBrowseTitle(playlist, 'playlist');
    const description = collectionDescription(playlist, title);
    const headerItemCount = browseHeaderItemCount(rawHeader(playlist));
    const totalItemCount = playlistItemCount(authoritativeItemCount) || playlistTotalItemCount(playlist, headerItemCount, rawBrowseDescription(playlist), `${page.tracks.length} tracks`);

    return {
      kind: 'playlist',
      browseId,
      title,
      subtitle: rawBrowseDescription(playlist),
      artist: '',
      year: '',
      itemCount: totalItemCount ? `${totalItemCount.count.toLocaleString('en-US')} tracks` : headerItemCount || (page.tracks.length ? `${page.tracks.length} tracks` : ''),
      totalTrackCount: totalItemCount?.count || 0,
      totalDuration: '',
      views: '',
      description,
      thumbnail: rawBrowseThumbnail(playlist),
      tracks: page.tracks,
      continuation: page.continuation,
      hasMoreTracks: page.hasMoreTracks,
      sections: []
    };
  }

  return {
    browseContinuationTokenFromData,
    browseHeaderItemCount,
    isExpandableBrowseSectionTitle,
    normalizeAlbum,
    normalizeBrowseSection,
    normalizePlaylist,
    normalizePlaylistPage,
    normalizeRawBrowseItem,
    normalizeRawResponsiveListItem,
    rawBrowseDescription,
    rawBrowseItemsFromData,
    rawBrowseThumbnail,
    rawHeader,
    rawMicroformat,
    rawSectionList
  };
}
