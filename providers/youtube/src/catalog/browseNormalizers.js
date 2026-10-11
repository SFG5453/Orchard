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

  function artistRunBrowseId(run) {
    const browseId = run?.navigationEndpoint?.browseEndpoint?.browseId || run?.endpoint?.payload?.browseId || '';
    return browseId.startsWith('UC') ? browseId : '';
  }
  function artistRunText(runs = []) {
    return runs.filter(artistRunBrowseId).map((run) => run.text).filter(Boolean).join(', ');
  }
  // Album rows carry no artist runs; the header link is the only artist id.
  function browseHeaderArtistId(header) {
    const runs = [
      ...(header?.strapline_text_one?.runs || header?.straplineTextOne?.runs || []),
      ...(header?.subtitle?.runs || [])
    ];
    return header?.author?.channel_id || runs.map(artistRunBrowseId).find(Boolean) || '';
  }
  function browseHeaderArtist(header) {
    return cleanedText(header?.author?.name) ||
      artistRunText(header?.strapline_text_one?.runs || header?.straplineTextOne?.runs) ||
      artistRunText(header?.subtitle?.runs) ||
      cleanedText(header?.facepile?.avatarStackViewModel?.text?.content) ||
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
  function unwrapBrowseHeader(value) {
    if (!value) return null;
    const editable = value.musicEditablePlaylistDetailHeaderRenderer;
    // Editable playlists wear an extra header. Apparently one hat was not enough.
    if (editable) {
      const header = unwrapBrowseHeader(editable.header) || editable.header || editable;
      // Private playlists can keep their prose in the editor instead of the display header.
      const editDescription = editable.editHeader?.musicPlaylistEditHeaderRenderer?.editDescription;
      // An empty editor says "Description". Helpful to humans, less helpful to parsers.
      const editorText = cleanedText(editDescription);
      const customDescription = normalizedLooseText(editorText) === 'description' ? '' : editorText;
      return {
        ...header,
        description: musicDescriptionShelfText(header.description) ? header.description : customDescription
      };
    }
    return value.musicResponsiveHeaderRenderer ||
      value.musicImmersiveHeaderRenderer ||
      value.musicDetailHeaderRenderer ||
      value.musicVisualHeaderRenderer || null;
  }

  function rawHeader(data) {
    const topHeader = unwrapBrowseHeader(data?.header);
    if (topHeader) return topHeader;

    // The page's generic header can coexist with the actual collection header.
    // Search every tab section before accepting that generic heading as metadata.
    const contents = data?.contents;
    const tabs = contents?.twoColumnBrowseResultsRenderer?.tabs ||
      contents?.singleColumnBrowseResultsRenderer?.tabs || [];
    for (const tab of tabs) {
      const sections = tab?.tabRenderer?.content?.sectionListRenderer?.contents || [];
      for (const section of sections) {
        const header = unwrapBrowseHeader(section);
        if (header) return header;
        for (const item of section?.itemSectionRenderer?.contents || []) {
          const nestedHeader = unwrapBrowseHeader(item);
          if (nestedHeader) return nestedHeader;
        }
      }
    }
    return data?.header?.musicHeaderRenderer || data?.header || {};
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
    const microformatArtist = mfTitle.includes(' - Album by ') ? mfTitle.split(' - Album by ').pop() : '';
    return browseHeaderArtist(header) || microformatArtist || '';
  }

  function musicDescriptionShelfText(value) {
    if (!value) return '';
    const shelf = value.musicDescriptionShelfRenderer;
    return asText(shelf ? shelf.description : (value.description || value));
  }

  function rawBrowseDescription(data, { includeMicroformatDescription = true, includeHeaderSubtitle = true } = {}) {
    const mf = rawMicroformat(data);
    const header = rawHeader(data);
    const headerDescription = musicDescriptionShelfText(header.description);
    return cleanedText(
      headerDescription ||
      (includeMicroformatDescription ? mf.description : '') ||
      (includeHeaderSubtitle ? header.subtitle : '')
    ) || '';
  }

  function isYoutubeMusicDescription(description = '', title = '') {
    const normalizedDescription = normalizedLooseText(description);
    const normalizedTitle = normalizedLooseText(title);
    if (!normalizedDescription.startsWith('listen to ')) return false;
    if (!normalizedDescription.includes(' on youtube music')) return false;

    return !normalizedTitle ||
      normalizedDescription.startsWith(`listen to ${normalizedTitle}`);
  }

  function collectionDescription(data, title = '', options) {
    const description = rawBrowseDescription(data, options);
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
      continuationTokenFromContinuations(data?.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.continuations) ||
      continuationTokenFromContinuations(data?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.continuations) ||
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

  // Extracts the audio playlist ID (OLAK5uy_...) needed by song.link / album.link.
  // Because MPREb_ is just YouTube's VIP backroom pass, and album.link only takes general admission.
  function extractAudioPlaylistId(album, browseId, header, tracks = []) {
    const canonicalUrl = album?.url ||
      rawMicroformat(album)?.urlCanonical ||
      rawMicroformat(album)?.url_canonical ||
      '';
    const canonicalMatch = String(canonicalUrl).match(/[?&]list=([a-zA-Z0-9_-]+)/);
    if (canonicalMatch && canonicalMatch[1]) {
      return canonicalMatch[1];
    }

    const buttons = header?.buttons || [];
    for (const button of buttons) {
      const playBtn = button?.musicPlayButtonRenderer || (button?.type === 'MusicPlayButton' ? button : null);
      const playlistId =
        playBtn?.playNavigationEndpoint?.watchEndpoint?.playlistId ||
        playBtn?.playNavigationEndpoint?.watchPlaylistEndpoint?.playlistId ||
        playBtn?.endpoint?.payload?.playlistId ||
        button?.navigationEndpoint?.watchEndpoint?.playlistId ||
        button?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId ||
        button?.endpoint?.payload?.playlistId;
      if (playlistId && typeof playlistId === 'string' && (playlistId.startsWith('OLAK') || playlistId.startsWith('PL'))) {
        return playlistId;
      }
    }

    const menuItems = header?.menu?.menuRenderer?.items || header?.menu?.items || [];
    for (const item of menuItems) {
      const ep = item?.menuNavigationItemRenderer?.navigationEndpoint || item?.endpoint;
      const playlistId = ep?.watchEndpoint?.playlistId || ep?.watchPlaylistEndpoint?.playlistId || ep?.payload?.playlistId;
      if (playlistId && typeof playlistId === 'string' && (playlistId.startsWith('OLAK') || playlistId.startsWith('PL'))) {
        return playlistId;
      }
    }

    for (const track of tracks) {
      if (track?.playlistId && typeof track.playlistId === 'string' && track.playlistId.startsWith('OLAK')) {
        return track.playlistId;
      }
    }
    if (tracks[0]?.playlistId && typeof tracks[0].playlistId === 'string') {
      return tracks[0].playlistId;
    }

    if (browseId && typeof browseId === 'string' && (browseId.startsWith('OLAK') || browseId.startsWith('PL'))) {
      return browseId;
    }

    return '';
  }

  function normalizeAlbum(album, browseId) {
    const thumbnail = rawBrowseThumbnail(album);
    const tracksSection = rawSectionList(album).find((section) => section.musicShelfRenderer)?.musicShelfRenderer;
    const tracks = (tracksSection?.contents || [])
      .map((item, index) => normalizeRawResponsiveListItem(item, index, rawBrowseArtistName(album)))
      .filter((item) => item.id)
      .map((track) => ({ ...track, thumbnail: track.thumbnail || thumbnail }));
    const artist = rawBrowseArtistName(album);
    const normalizedArtist = normalizedLooseText(artist);
    const artistBrowseId = tracks
      .map((track) => {
        const artistIndex = track.artists?.findIndex((name) => normalizedLooseText(name) === normalizedArtist) ?? -1;
        return artistIndex >= 0 ? track.artistBrowseIds?.[artistIndex] : '';
      })
      .find(Boolean) || tracks.find((track) => track.artistBrowseIds?.[0])?.artistBrowseIds?.[0] ||
      browseHeaderArtistId(rawHeader(album));
    const title = rawBrowseTitle(album, 'album');
    const description = collectionDescription(album, title, { includeMicroformatDescription: false, includeHeaderSubtitle: false });
    const header = rawHeader(album);
    const explicit = hasExplicitBadge(header) || tracks.some((track) => track.explicit);
    const audioPlaylistId = extractAudioPlaylistId(album, browseId, header, tracks);

    return {
      kind: 'album',
      browseId,
      audioPlaylistId,
      playlistId: audioPlaylistId,
      title,
      subtitle: rawBrowseDescription(album),
      artist,
      artistBrowseId,
      releaseType: releaseTypeFromText(
        asText(header.subtitle),
        asText(header.second_subtitle),
        tracks.length ? `${tracks.length} tracks` : ''
      ),
      explicit,
      year: browseHeaderYear(header),
      itemCount: tracks.length ? `${tracks.length} tracks` : '',
      totalDuration: '',
      views: '',
      description,
      thumbnail,
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
    const rawDescription = collectionDescription(playlist, title, { includeHeaderSubtitle: false });
    // Microformat tags describe the page type, not the creator's playlist.
    const description = /^playlist(?:\s*[•·]|$)/i.test(rawDescription) ? '' : rawDescription;
    const headerItemCount = browseHeaderItemCount(rawHeader(playlist));
    const totalItemCount = playlistItemCount(authoritativeItemCount) || playlistTotalItemCount(playlist, headerItemCount, rawBrowseDescription(playlist), `${page.tracks.length} tracks`);
    const cleanPlaylistId = browseId && browseId.startsWith('VL') ? browseId.slice(2) : (browseId || '');

    return {
      kind: 'playlist',
      browseId,
      playlistId: cleanPlaylistId,
      audioPlaylistId: cleanPlaylistId,
      title,
      subtitle: rawBrowseDescription(playlist),
      author: rawBrowseArtistName(playlist) ||
        cleanedText(rawHeader(playlist).straplineTextOne || rawHeader(playlist).strapline_text_one),
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
    playlistContinuationTokenFromData,
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
