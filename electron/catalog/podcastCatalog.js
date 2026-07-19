// Normalizes podcast shows and episodes into the renderer catalog model.
import { asText, bestThumbnail } from './musicText.js';

const PODCAST_PAGE_TYPE = 'MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE';
const PODCAST_EPISODE_TYPE = 'MUSIC_VIDEO_TYPE_PODCAST_EPISODE';

function sectionList(data) {
  return data?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents ||
    data?.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents ||
    [];
}

function sectionNode(section) {
  return section?.musicCarouselShelfRenderer ||
    section?.musicShelfRenderer ||
    section?.gridRenderer ||
    null;
}

function sectionTitle(node) {
  return asText(
    node?.header?.musicCarouselShelfBasicHeaderRenderer?.title ||
    node?.header?.musicShelfHeaderRenderer?.title ||
    node?.title
  ) || 'Podcasts';
}

function browsePageType(endpoint = {}) {
  return endpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs
    ?.browseEndpointContextMusicConfig?.pageType || '';
}

function podcastShow(item) {
  const renderer = item?.musicTwoRowItemRenderer || item?.musicResponsiveListItemRenderer;
  if (!renderer) return null;
  const titleRun = renderer.title?.runs?.[0] ||
    renderer.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0] ||
    {};
  const endpoint = renderer.navigationEndpoint || titleRun.navigationEndpoint || {};
  const browse = endpoint.browseEndpoint || {};
  if (browsePageType(endpoint) !== PODCAST_PAGE_TYPE && !browse.browseId?.startsWith('MPSP')) return null;

  const subtitle = asText(renderer.subtitle) ||
    asText(renderer.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text);

  return {
    id: null,
    browseId: browse.browseId || null,
    browsePayload: browse.browseId ? { ...browse } : null,
    type: 'podcast',
    title: asText(renderer.title) || asText(titleRun.text) || 'Podcast',
    subtitle,
    artists: [],
    artistBrowseIds: [],
    artist: '',
    album: '',
    albumId: null,
    duration: '',
    durationSeconds: 0,
    explicit: false,
    year: '',
    views: '',
    itemCount: '',
    thumbnail: bestThumbnail(renderer.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail || renderer.thumbnail)
  };
}

function podcastEpisode(item, index = 0, showTitle = '') {
  const renderer = item?.musicMultiRowListItemRenderer;
  if (!renderer) return null;
  const watch = renderer.onTap?.watchEndpoint ||
    renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint ||
    {};
  if (!watch.videoId) return null;
  const duration = asText(renderer.secondTitle).match(/\b\d{1,2}:\d{2}(?::\d{2})?\b/)?.[0] || '';

  return {
    id: watch.videoId,
    browseId: renderer.title?.runs?.[0]?.navigationEndpoint?.browseEndpoint?.browseId || null,
    browsePayload: null,
    type: 'podcast_episode',
    musicVideoType: watch.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType || PODCAST_EPISODE_TYPE,
    mediaKind: 'audio',
    isAudioOnly: true,
    title: asText(renderer.title) || 'Untitled episode',
    subtitle: asText(renderer.subtitle),
    description: asText(renderer.description),
    artists: showTitle ? [showTitle] : [],
    artistBrowseIds: [],
    artist: showTitle,
    album: showTitle,
    albumId: null,
    duration,
    durationSeconds: 0,
    explicit: false,
    year: '',
    views: '',
    itemCount: '',
    thumbnail: bestThumbnail(renderer.thumbnail?.musicThumbnailRenderer?.thumbnail || renderer.thumbnail),
    index: String(index + 1)
  };
}

function normalizedSection(section, index) {
  const node = sectionNode(section);
  if (!node) return null;
  const title = sectionTitle(node);
  const items = (node.contents || node.items || [])
    .map((item, itemIndex) => podcastShow(item) || podcastEpisode(item, itemIndex))
    .filter(Boolean);

  return items.length ? {
    key: `podcasts-${index}`,
    title,
    items
  } : null;
}

function responsiveHeader(data) {
  const contents = data?.contents?.twoColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];
  return contents.find((item) => item.musicResponsiveHeaderRenderer)?.musicResponsiveHeaderRenderer || {};
}

export function normalizePodcastFeed(data) {
  return {
    title: 'Podcasts',
    sections: sectionList(data).map(normalizedSection).filter(Boolean)
  };
}

export function normalizePodcastDetail(data, browseId) {
  const header = responsiveHeader(data);
  const microformat = data?.microformat?.microformatDataRenderer || {};
  const title = asText(header.title) || asText(microformat.title) || 'Podcast';
  const artist = asText(header.straplineTextOne);
  const tracks = sectionList(data)
    .flatMap((section) => sectionNode(section)?.contents || [])
    .map((item, index) => podcastEpisode(item, index, title))
    .filter(Boolean);

  return {
    kind: 'podcast',
    browseId,
    title,
    subtitle: artist,
    artist,
    explicit: false,
    year: '',
    itemCount: tracks.length ? `${tracks.length} episodes` : '',
    totalDuration: '',
    views: '',
    description: '',
    thumbnail: bestThumbnail(header.thumbnail?.musicThumbnailRenderer?.thumbnail || microformat.thumbnail),
    tracks,
    sections: []
  };
}
