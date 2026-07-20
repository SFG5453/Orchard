// Centralizes text, thumbnail, duration, and track normalization for catalog modules.
import { isUploadedMusicItem } from './musicItemTypes.js';
export function bestThumbnail(thumbnails = []) {
  const list = Array.isArray(thumbnails) ? thumbnails : thumbnails?.contents || thumbnails?.thumbnails || [];
  const sorted = [...list].sort((a, b) => (b.width || 0) - (a.width || 0));
  return sorted[0]?.url || null;
}

export function asText(value) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  if (typeof value.simpleText === 'string') return value.simpleText;
  if (typeof value.text === 'string') return value.text;
  if (Array.isArray(value.runs)) return value.runs.map((run) => run.text).join('');
  if (typeof value.toString === 'function' && value.toString !== Object.prototype.toString) return value.toString();
  return '';
}

export function cleanedText(value) {
  const text = asText(value).trim();
  return text === '[object Object]' ? '' : text;
}

export function textParts(value) {
  return cleanedText(value)
    .split('•')
    .map((part) => part.trim())
    .filter(Boolean);
}

function explicitBadgeText(badge) {
  const value = badge?.musicInlineBadgeRenderer || badge?.music_inline_badge_renderer || badge;
  return [
    value?.icon_type,
    value?.iconType,
    value?.icon?.iconType,
    value?.label,
    value?.accessibility?.accessibility_data?.label,
    value?.accessibility?.accessibilityData?.label,
    value?.accessibilityData?.accessibilityData?.label
  ].map((value) => String(value || '').toLowerCase()).join(' ');
}

export function hasExplicitBadge(value = {}) {
  const list = [
    value.badges,
    value.badge,
    value.subtitle_badges,
    value.subtitleBadges,
    value.musicResponsiveListItemRenderer?.badges,
    value.musicTwoRowItemRenderer?.subtitleBadges,
    value.musicDetailHeaderRenderer?.subtitleBadges,
    value.musicResponsiveHeaderRenderer?.subtitleBadges
  ].flatMap((badges) => Array.isArray(badges) ? badges : badges ? [badges] : []);

  return list.some((badge) => /explicit|music_explicit_badge/.test(explicitBadgeText(badge)));
}

export function normalizedLooseText(value = '') {
  return String(value || '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

export function textMatchesArtist(value = '', artist = '') {
  const text = normalizedLooseText(value);
  const target = normalizedLooseText(artist);
  return Boolean(text && target && (text === target || text.includes(target) || target.includes(text)));
}

export function textMatchesTitle(value = '', title = '') {
  const text = normalizedLooseText(value);
  const target = normalizedLooseText(title);
  return Boolean(text && target && text === target);
}

export function formatMillisDuration(milliseconds = 0) {
  const totalSeconds = Math.round(Number(milliseconds || 0) / 1000);
  if (!Number.isFinite(totalSeconds) || totalSeconds <= 0) return '';

  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : `${minutes}:${String(seconds).padStart(2, '0')}`;
}

function durationText(value = '') {
  return String(value || '').match(/\b\d{1,2}:\d{2}(?::\d{2})?\b/)?.[0] || '';
}

export function findDurationText(value, depth = 0) {
  if (!value || depth > 5) return '';
  if (typeof value === 'string') return durationText(value);
  if (typeof value === 'number') return '';

  const directText = durationText(asText(value));
  if (directText) return directText;

  if (Array.isArray(value)) {
    for (const item of value) {
      const text = findDurationText(item, depth + 1);
      if (text) return text;
    }
    return '';
  }

  if (typeof value === 'object') {
    for (const item of Object.values(value)) {
      const text = findDurationText(item, depth + 1);
      if (text) return text;
    }
  }

  return '';
}

function rawLineText(line) {
  const items = line?.lineRenderer?.items || [];
  return items
    .map((item) => asText(item.lineItemRenderer?.text))
    .filter((text) => text && text !== '•')
    .join(' ');
}

function tvShelfTitle(shelf, index) {
  const avatar = shelf?.headerRenderer?.shelfHeaderRenderer?.avatarLockup?.avatarLockupRenderer;
  const explicit = asText(avatar?.title || shelf?.title);
  if (explicit) return explicit;
  return index === 0 ? 'Library' : 'Items';
}

function normalizeTvTile(tile) {
  const command = tile?.onSelectCommand || {};
  const watch = command.watchEndpoint || {};
  const browse = command.browseEndpoint || {};
  const metadata = tile?.metadata?.tileMetadataRenderer || {};
  const lines = metadata.lines || [];
  const subtitle = lines.map(rawLineText).filter(Boolean).join(' • ');
  const contentType = tile?.contentType || '';

  return {
    id: watch.videoId || (contentType === 'TILE_CONTENT_TYPE_VIDEO' ? tile?.contentId : null),
    browseId: browse.browseId || (contentType !== 'TILE_CONTENT_TYPE_VIDEO' ? tile?.contentId : null),
    browsePayload: browse.browseId ? { ...browse } : null,
    type: contentType.replace('TILE_CONTENT_TYPE_', '').toLowerCase() || 'library',
    title: asText(metadata.title) || asText(tile?.header?.tileHeaderRenderer?.metadata?.tileMetadataRenderer?.title) || tile?.contentId || 'Untitled',
    subtitle,
    artists: subtitle ? [subtitle.split(' • ')[0]] : [],
    album: '',
    albumId: null,
    duration: '',
    durationSeconds: 0,
    year: '',
    views: '',
    itemCount: '',
    thumbnail: bestThumbnail(tile?.header?.tileHeaderRenderer?.thumbnail)
  };
}

export function normalizeTvLibrary(data) {
  const shelves =
    data?.contents?.tvBrowseRenderer?.content?.tvSurfaceContentRenderer?.content?.sectionListRenderer?.contents || [];

  return {
    filters: [],
    sections: shelves
      .map((entry, index) => {
        const shelf = entry.shelfRenderer;
        const items = shelf?.content?.horizontalListRenderer?.items || [];
        const normalizedItems = items
          .map((item) => item.tileRenderer && normalizeTvTile(item.tileRenderer))
          .filter(Boolean)
          .filter((item) => item.id || item.browseId || item.title !== 'Untitled');

        return {
          key: `tv-library-${index}`,
          title: tvShelfTitle(shelf, index),
          items: normalizedItems
        };
      })
      .map((section) => {
        if (section.title === 'Playlists') return section;
        return {
          ...section,
          items: section.items.filter((item) => item.type === 'playlist')
        };
      })
      .filter((section) => section.title === 'Playlists' || section.items.some((item) => item.type === 'playlist'))
      .filter((section) => section.items.length > 0)
  };
}

export function normalizeTvPlaylist(data) {
  const root = data?.contents?.tvBrowseRenderer?.content?.tvSurfaceContentRenderer?.content?.twoColumnRenderer;
  const meta = root?.leftColumn?.entityMetadataRenderer || {};
  const bylines = meta.bylines || [];
  const subtitle = bylines
    .map(rawLineText)
    .filter(Boolean)
    .join(' • ');
  const items = root?.rightColumn?.playlistVideoListRenderer?.contents || [];

  return {
    title: asText(meta.title) || 'Playlist',
    subtitle,
    thumbnail: bestThumbnail(meta.thumbnail),
    sections: [
      {
        key: 'playlist-tracks',
        title: 'Tracks',
        items: items
          .map((item) => item.tileRenderer && normalizeTvTile(item.tileRenderer))
          .filter(Boolean)
          .filter((item) => item.id)
      }
    ]
  };
}

export function normalizeTrack(item) {
  const artists = item.artists || item.authors || (item.author ? [item.author] : []);
  const endpoint = item.endpoint || item.on_tap || item.overlay?.content?.endpoint || item.thumbnail_overlay?.content?.endpoint;
  const payload = endpoint?.payload || {};
  const musicVideoType = payload.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType || item.music_video_type || '';
  const thumbnails = item.thumbnails || item.thumbnail?.contents || item.thumbnail || [];
  const title = asText(item.title) ||
    item.name ||
    asText(item.flex_columns?.[0]?.title) ||
    asText(item.author?.name) ||
    'Untitled';

  const normalized = {
    id: item.id || item.video_id || payload.videoId || null,
    browseId: payload.browseId || null,
    browsePayload: payload.browseId ? { ...payload } : null,
    type: item.item_type || item.type || 'track',
    musicVideoType,
    isAudioOnly: musicVideoType === 'MUSIC_VIDEO_TYPE_ATV' || item.item_type === 'song',
    title,
    subtitle: asText(item.subtitle) || asText(item.flex_columns?.[1]?.title),
    artists: artists.map((artist) => typeof artist === 'string' ? artist : artist.name).filter(Boolean),
    artistBrowseIds: artists.map((artist) => artist?.channel_id).filter(Boolean),
    album: item.album?.name || '',
    albumId: item.album?.id || null,
    duration: item.duration?.text || asText(item.fixed_columns?.[0]?.title) || '',
    durationSeconds: item.duration?.seconds || 0,
    explicit: hasExplicitBadge(item),
    year: item.year || '',
    views: item.views || '',
    itemCount: asText(item.item_count || item.itemCount || item.song_count || item.video_count || item.video_count_short),
    thumbnail: bestThumbnail(thumbnails)
  };

  return {
    ...normalized,
    isUpload: isUploadedMusicItem({
      ...normalized,
      entityId: item.entityId || item.entity_id,
      menu: item.menu
    })
  };
}
