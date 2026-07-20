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
      .find(durationText) || findDurationText(renderer.fixedColumns);
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
    const thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail || renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail || renderer.thumbnail || [];
    const artistNames = artistRuns.map((run) => asText(run.text)).filter(Boolean);
    const artistBrowseIds = artistRuns.map((run) => run.navigationEndpoint?.browseEndpoint?.browseId).filter(Boolean);
    const artistName = artistNames[0] || fallbackArtist || '';

    const normalized = {
      id: watchEndpoint?.videoId || renderer.playlistItemData?.videoId || null,
      browseId: browsePayload?.browseId || null,
      browsePayload: browsePayload ? { ...browsePayload } : null,
      type: watchEndpoint?.videoId ? 'track' : (browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType || 'track').replace('MUSIC_PAGE_TYPE_', '').toLowerCase(),
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
      durationSeconds: 0,
      explicit: hasExplicitBadge(renderer),
      year: '',
      views: '',
      itemCount: textParts(asText(renderer.subtitle)).find((part) => /\b(songs?|tracks?|videos?)\b/i.test(part)) || '',
      thumbnail: bestThumbnail(thumbnail),
      index: asText(renderer.index) || String(index + 1)
    };

    return {
      ...normalized,
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
    const musicVideoType = watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType || '';
    const pageType = browsePayload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
    const artistRuns = subtitleRuns.filter(isArtistBrowseRun);
    const artistNames = artistRuns.map((run) => asText(run.text)).filter(Boolean);
    const thumbnail = renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail || renderer.thumbnail || [];
    const hasVideo = Boolean(watchEndpoint?.videoId);

    const normalized = {
      id: watchEndpoint?.videoId || null,
      browseId: browsePayload?.browseId || null,
      browsePayload: browsePayload ? { ...browsePayload } : null,
      type: hasVideo
        ? (musicVideoType === 'MUSIC_VIDEO_TYPE_ATV' ? 'track' : 'video')
        : pageType === 'MUSIC_PAGE_TYPE_ARTIST'
        ? 'artist'
        : pageType === 'MUSIC_PAGE_TYPE_ALBUM'
          ? 'album'
          : pageType === 'MUSIC_PAGE_TYPE_PLAYLIST'
            ? 'playlist'
            : 'media',
      musicVideoType,
      isAudioOnly: musicVideoType === 'MUSIC_VIDEO_TYPE_ATV',
      title: asText(renderer.title) || 'Untitled',
      subtitle: asText(renderer.subtitle),
      artists: artistNames,
      artistBrowseIds: artistRuns.map((run) => run.navigationEndpoint?.browseEndpoint?.browseId).filter(Boolean),
      artist: artistNames[0] || '',
      album: '',
      albumId: null,
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
