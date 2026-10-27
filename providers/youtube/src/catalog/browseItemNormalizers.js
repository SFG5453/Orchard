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

// Normalizes individual raw browse renderers into Orchard catalog items.
import { isUploadedMusicItem } from './musicItemTypes.js';
export function createBrowseItemNormalizers({
  asText,
  bestThumbnail,
  findDurationText,
  hasExplicitBadge,
  textParts
}) {
  const durationText = (value = '') => String(value || '').match(/\b\d{1,2}:\d{2}(?::\d{2})?\b/)?.[0] || '';

  // A release tile can have both a browse endpoint (open the album) and a
  // thumbnail play endpoint (start its first song). The play endpoint must not
  // turn that tile into a track or hide its album navigation actions.
  function browseMediaType(browsePayload) {
    const pageType = browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType || '';
    const browseId = browsePayload?.browseId || '';
    if (pageType === 'MUSIC_PAGE_TYPE_ALBUM' || browseId.startsWith('MPR') ||
        browseId.startsWith('FEmusic_library_privately_owned_release')) return 'album';
    if (pageType === 'MUSIC_PAGE_TYPE_ARTIST' || pageType === 'MUSIC_PAGE_TYPE_LIBRARY_ARTIST' ||
        browseId.startsWith('FEmusic_library_privately_owned_artist')) return 'artist';
    if (pageType === 'MUSIC_PAGE_TYPE_PLAYLIST' || pageType === 'MUSIC_PAGE_TYPE_USER_PLAYLIST') return 'playlist';
    return '';
  }

  function isArtistBrowseRun(run) {
    const browseId = run?.navigationEndpoint?.browseEndpoint?.browseId || '';
    const pageType = run?.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    return pageType === 'MUSIC_PAGE_TYPE_ARTIST' ||
      pageType === 'MUSIC_PAGE_TYPE_USER_CHANNEL' ||
      browseId.startsWith('UC') ||
      browseId.startsWith('FEmusic_library_privately_owned_artist');
  }

  function isAlbumBrowseRun(run) {
    const browseId = run?.navigationEndpoint?.browseEndpoint?.browseId || '';
    const pageType = run?.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    return pageType === 'MUSIC_PAGE_TYPE_ALBUM' ||
      browseId.startsWith('MPR') ||
      browseId.startsWith('FEmusic_library_privately_owned_release');
  }

  // Some catalog acts have no channel, so their credit is plain text ahead of the album.
  function unlinkedArtistNames(runs = []) {
    const segments = [[]];
    for (const run of runs) {
      if (run.text?.trim() === '•') segments.push([]);
      else segments.at(-1).push(run);
    }
    const credit = /^(song|video|episode|podcast)$/i.test(asText({ runs: segments[0] }).trim())
      ? segments[1] : segments[0];
    if (!credit?.length || credit.some((run) => run.navigationEndpoint)) return [];
    const name = asText({ runs: credit }).trim();
    if (!name || durationText(name) || /\b(views?|plays?)$/i.test(name)) return [];
    return [name];
  }

  function normalizeRawResponsiveListItem(item, index = 0, fallbackArtist = '') {
    const renderer = item?.musicResponsiveListItemRenderer || item || {};
    const titleColumn = renderer.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text || {};
    const titleRuns = titleColumn.runs || [];
    const titleRun = titleRuns[0] || {};
    const subtitleRuns = [
      ...(renderer.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || []),
      ...(renderer.flexColumns?.[2]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [])
    ];
    const artistRuns = subtitleRuns.filter(isArtistBrowseRun);
    const albumRun = subtitleRuns.find(isAlbumBrowseRun);
    const duration = renderer.fixedColumns
      ?.map((column) => asText(column?.musicResponsiveListItemFixedColumnRenderer?.text))
      .find(durationText) || findDurationText(renderer.fixedColumns) ||
      findDurationText(subtitleRuns);
    // Search puts the clock beside the credits; album tracklists use fixed columns.
    const durationSeconds = duration
      ? duration.split(':').reduce((total, part) => total * 60 + Number(part), 0)
      : 0;
    const titleWatchEndpoint = titleRun.navigationEndpoint?.watchEndpoint || {};
    const overlayWatchEndpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint || {};
    const rendererWatchEndpoint = renderer.navigationEndpoint?.watchEndpoint || {};
    const rawWatchEndpoint = titleWatchEndpoint.videoId
      ? titleWatchEndpoint
      : overlayWatchEndpoint.videoId
        ? overlayWatchEndpoint
        : rendererWatchEndpoint;
    const musicVideoType = rawWatchEndpoint.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType || '';
    const watchEndpoint = (rawWatchEndpoint.videoId || renderer.playlistItemData?.videoId)
      ? {
        videoId: renderer.playlistItemData?.videoId || rawWatchEndpoint.videoId || null,
        playlistId: rawWatchEndpoint.playlistId || null
      }
      : null;
    const browsePayload = titleRun.navigationEndpoint?.browseEndpoint || renderer.navigationEndpoint?.browseEndpoint || null;
    const collectionType = browseMediaType(browsePayload);
    const thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail || renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail || renderer.thumbnail || [];
    const linkedArtistNames = artistRuns.map((run) => asText(run.text)).filter(Boolean);
    const artistNames = linkedArtistNames.length || collectionType || !watchEndpoint?.videoId
      ? linkedArtistNames
      : unlinkedArtistNames(renderer.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs);
    const artistBrowseIds = artistRuns.map((run) => run.navigationEndpoint?.browseEndpoint?.browseId).filter(Boolean);
    const artistName = artistNames[0] || fallbackArtist || '';

    const normalized = {
      id: collectionType ? null : (watchEndpoint?.videoId || renderer.playlistItemData?.videoId || null),
      browseId: browsePayload?.browseId || null,
      browsePayload: browsePayload ? { ...browsePayload } : null,
      // A release's thumbnail play button can point at a song. Prefer the
      // release browse endpoint when both are present.
      type: collectionType || (watchEndpoint?.videoId
        ? (['MUSIC_VIDEO_TYPE_OMV', 'MUSIC_VIDEO_TYPE_UGC'].includes(musicVideoType) ? 'video' : 'track')
        : (browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType || 'track').replace('MUSIC_PAGE_TYPE_', '').toLowerCase()),
      musicVideoType,
      isAudioOnly: musicVideoType === 'MUSIC_VIDEO_TYPE_ATV',
      title: asText(titleColumn) || 'Untitled',
      subtitle: asText(renderer.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text || renderer.flexColumns?.[2]?.musicResponsiveListItemFlexColumnRenderer?.text),
      artists: artistNames.length ? artistNames : (artistName ? [artistName] : []),
      artistBrowseIds,
      artist: artistName,
      album: albumRun ? asText(albumRun.text) : '',
      albumId: albumRun?.navigationEndpoint?.browseEndpoint?.browseId || null,
      duration,
      durationSeconds,
      explicit: hasExplicitBadge(renderer),
      year: '',
      views: '',
      itemCount: textParts(asText(renderer.subtitle)).find((part) => /\b(songs?|tracks?|videos?)\b/i.test(part)) || '',
      thumbnail: bestThumbnail(thumbnail),
      index: asText(renderer.index) || String(index + 1),
      playlistId: watchEndpoint?.playlistId || null
    };

    // Playlist rows carry the entry id that removal needs; duplicates share a videoId.
    const setVideoId = renderer.playlistItemData?.playlistSetVideoId;
    return {
      ...normalized,
      ...(setVideoId ? { setVideoId } : {}),
      isUpload: isUploadedMusicItem({ ...normalized, menu: renderer.menu })
    };
  }

  function normalizeRawTwoRowItem(item, index = 0) {
    const renderer = item?.musicTwoRowItemRenderer || item || {};
    const titleRun = renderer.title?.runs?.[0] || {};
    const subtitleRuns = renderer.subtitle?.runs || [];
    const overlayWatchEndpoint = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint || {};
    const watchEndpoint = renderer.navigationEndpoint?.watchEndpoint ||
      titleRun.navigationEndpoint?.watchEndpoint ||
      overlayWatchEndpoint ||
      null;
    const browsePayload = renderer.navigationEndpoint?.browseEndpoint || titleRun.navigationEndpoint?.browseEndpoint || null;
    const collectionType = browseMediaType(browsePayload);
    const musicVideoType = watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType || '';
    const artistRuns = subtitleRuns.filter(isArtistBrowseRun);
    const artistNames = artistRuns.map((run) => asText(run.text)).filter(Boolean);
    const thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail || renderer.thumbnail || [];
    const hasVideo = Boolean(watchEndpoint?.videoId);

    const normalized = {
      id: collectionType ? null : (watchEndpoint?.videoId || null),
      browseId: browsePayload?.browseId || null,
      browsePayload: browsePayload ? { ...browsePayload } : null,
      type: collectionType || (hasVideo
        ? (musicVideoType === 'MUSIC_VIDEO_TYPE_ATV' ? 'track' : 'video')
        : 'media'),
      musicVideoType,
      isAudioOnly: musicVideoType === 'MUSIC_VIDEO_TYPE_ATV',
      title: asText(renderer.title) || 'Untitled',
      subtitle: asText(renderer.subtitle),
      artists: artistNames,
      artistBrowseIds: artistRuns.map((run) => run.navigationEndpoint?.browseEndpoint?.browseId).filter(Boolean),
      artist: artistNames[0] || '',
      album: '',
      albumId: null,
      playlistId: watchEndpoint?.playlistId || null,
      audioPlaylistId: collectionType === 'album' ? (watchEndpoint?.playlistId || null) : null,
      duration: '',
      durationSeconds: 0,
      explicit: hasExplicitBadge(renderer),
      year: asText(renderer.subtitle),
      views: '',
      itemCount: textParts(asText(renderer.subtitle)).find((part) => /\b(songs?|tracks?|videos?)\b/i.test(part)) || '',
      thumbnail: bestThumbnail(thumbnail),
      index: String(index + 1)
    };

    return {
      ...normalized,
      isUpload: isUploadedMusicItem({ ...normalized, menu: renderer.menu })
    };
  }

  function normalizeRawBrowseItem(item, index = 0) {
    if (item?.musicResponsiveListItemRenderer) return normalizeRawResponsiveListItem(item, index);
    if (item?.musicTwoRowItemRenderer) return normalizeRawTwoRowItem(item, index);
    return null;
  }

  return {
    isAlbumBrowseRun,
    isArtistBrowseRun,
    normalizeRawBrowseItem,
    normalizeRawResponsiveListItem,
    normalizeRawTwoRowItem
  };
}
