// Selects compatible YouTube audio/video formats from renderer capability hints.
const fallbackMimePreference = [
  'audio/mp4; codecs="mp4a.40.2"',
  'audio/mp4; codecs="mp4a.40.5"',
  'audio/webm; codecs="opus"',
  'audio/webm; codecs="vorbis"'
];

const fallbackVideoMimePreference = [
  'video/mp4; codecs="avc1.640028"',
  'video/mp4; codecs="avc1.64001F"',
  'video/mp4; codecs="avc1.4d401F"',
  'video/mp4; codecs="avc1.42001E"',
  'video/mp4; codecs="avc1.64001F, mp4a.40.2"',
  'video/mp4; codecs="avc1.4d401F, mp4a.40.2"',
  'video/mp4; codecs="avc1.42001E, mp4a.40.2"',
  'video/mp4',
  'video/webm; codecs="vp9"',
  'video/webm; codecs="vp8"',
  'video/webm'
];

export function createPreferredAudioTrack({ normalizedLookupText, shelfItems }) {
  function durationSeconds(item = {}) {
    const direct = Number(item.durationSeconds || item.duration?.seconds || 0);
    if (direct > 0) return Math.round(direct);
    const parts = String(item.duration || '').trim().split(':').map(Number);
    if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
    return parts.reduce((total, part) => total * 60 + part, 0);
  }

  function durationMatches(candidate, target) {
    const candidateDuration = durationSeconds(candidate);
    const targetDuration = durationSeconds(target);
    if (!candidateDuration || !targetDuration) return null;
    const tolerance = Math.max(5, Math.round(targetDuration * 0.05));
    return Math.abs(candidateDuration - targetDuration) <= tolerance;
  }

  function titleVariantQualifiers(value = '') {
    const normalized = normalizedLookupText(value);
    const qualifiers = [
      'live',
      'acoustic',
      'remix',
      'demo',
      'instrumental',
      'karaoke',
      'cover',
      'sped up',
      'slowed',
      'reverb',
      'extended',
      'edit',
      'version'
    ];

    return qualifiers.filter((qualifier) => {
      const pattern = new RegExp(`(^|\\s)${qualifier.replace(/\s+/g, '\\s+')}($|\\s)`);
      return pattern.test(normalized);
    });
  }

  function trackMatchScore(candidate, target) {
    const candidateTitle = normalizedLookupText(candidate.title);
    const targetTitle = normalizedLookupText(target.title);
    const candidateArtist = normalizedLookupText(candidate.artist || candidate.artists?.[0] || '');
    const targetArtist = normalizedLookupText(target.artist || target.artists?.[0] || '');
    const targetQualifiers = titleVariantQualifiers(target.title);
    const candidateQualifiers = titleVariantQualifiers(candidate.title);
    const matchingDuration = durationMatches(candidate, target);
    const titleTokenCount = targetTitle.split(/\s+/).filter(Boolean).length;
    let score = 0;

    if (!candidateTitle || !targetTitle || candidateTitle !== targetTitle) return -Infinity;
    if (candidateArtist && targetArtist && candidateArtist !== targetArtist) return -Infinity;
    if (targetQualifiers.some((qualifier) => !candidateQualifiers.includes(qualifier))) return -Infinity;
    if (matchingDuration === false) return -Infinity;
    if (titleTokenCount <= 1 && matchingDuration !== true && target.album && candidate.album && normalizedLookupText(candidate.album) !== normalizedLookupText(target.album)) {
      return -Infinity;
    }
    if (candidate.musicVideoType === 'MUSIC_VIDEO_TYPE_ATV' || candidate.isAudioOnly) score += 6;
    if (candidate.explicit) score += 4;
    else if (target.explicit) score -= 4;
    score += 5;
    if (candidateArtist && targetArtist) score += 3;
    if (candidate.album && target.album && normalizedLookupText(candidate.album) === normalizedLookupText(target.album)) score += 3;
    if (matchingDuration) score += 4;

    return score;
  }

  return async function preferredAudioTrack(yt, target = {}) {
    const alreadyPreferredAudio = target.musicVideoType === 'MUSIC_VIDEO_TYPE_ATV' || target.isAudioOnly;
    const excludedVideoIds = new Set([target.videoId, ...(target.excludedVideoIds || [])].filter(Boolean));

    if (target.isUpload || !target.videoId || !target.preferAudioOnly || (alreadyPreferredAudio && !target.retryAlternateAudio)) {
      return target.videoId;
    }

    const query = [target.title, target.artist || target.artists?.[0]]
      .filter(Boolean)
      .join(' ')
      .trim();
    if (!query) return target.videoId;

    try {
      const search = await yt.music.search(query, { type: 'song' });
      const candidates = shelfItems(search.songs)
        .filter((item) =>
          item.id &&
          !excludedVideoIds.has(item.id) &&
          (item.musicVideoType === 'MUSIC_VIDEO_TYPE_ATV' || item.isAudioOnly)
        )
        .map((item) => ({ item, score: trackMatchScore(item, target) }))
        .sort((a, b) => b.score - a.score);
      const best = candidates[0];

      if (best?.score >= 8 && (target.retryAlternateAudio || best.item.explicit || target.musicVideoType !== 'MUSIC_VIDEO_TYPE_ATV')) return best.item.id;
    } catch (error) {
      console.warn(`Could not resolve audio-only track for ${target.videoId}: ${error.message}`);
    }

    return target.videoId;
  };
}

function trackTitle(info) {
  return info.basic_info?.title || info.video_details?.title || 'Untitled';
}

function trackAuthor(info) {
  return info.basic_info?.author || info.video_details?.author || '';
}

export function createTrackInfoNormalizer({ bestThumbnail }) {
  return function normalizeTrackInfo(videoId, info) {
    const basicInfo = info.basic_info || {};
    const videoDetails = info.video_details || {};

    return {
      id: videoId,
      title: trackTitle(info),
      artist: trackAuthor(info),
      durationSeconds: Number(basicInfo.duration || videoDetails.length_seconds || 0),
      thumbnail: bestThumbnail(basicInfo.thumbnail || videoDetails.thumbnails || []),
      isLive: Boolean(basicInfo.is_live || basicInfo.isLiveContent || videoDetails.is_live || videoDetails.isLiveContent)
    };
  };
}

export function playableAudioFormats(info) {
  return (info.streaming_data?.adaptive_formats || [])
    .filter((format) => format.has_audio && !format.has_video && format.mime_type)
    .sort((a, b) => (b.bitrate || 0) - (a.bitrate || 0));
}

export function playableVideoFormats(info) {
  return (info.streaming_data?.formats || [])
    .filter((format) => format.has_audio && format.has_video && format.mime_type)
    .sort(compareVideoFormats);
}

export function playableVideoOnlyFormats(info) {
  return (info.streaming_data?.adaptive_formats || [])
    .filter((format) => format.has_video && !format.has_audio && format.mime_type)
    .sort(compareVideoFormats);
}

export function rawPlayableAudioFormats(formats = []) {
  return formats
    .map((format) => ({
      itag: format.itag,
      mime_type: format.mimeType,
      bitrate: format.bitrate || format.averageBitrate || 0,
      average_bitrate: format.averageBitrate || format.bitrate || 0,
      content_length: Number(format.contentLength || 0),
      url: format.url
    }))
    .filter((format) => format.url && format.mime_type?.startsWith('audio/'))
    .sort((a, b) => (b.bitrate || 0) - (a.bitrate || 0));
}

export function rawPlayableVideoFormats(formats = []) {
  return formats
    .map((format) => ({
      itag: format.itag,
      mime_type: format.mimeType,
      quality_label: format.qualityLabel || format.quality || '',
      width: Number(format.width || 0),
      height: Number(format.height || 0),
      fps: Number(format.fps || 0),
      bitrate: format.bitrate || format.averageBitrate || 0,
      average_bitrate: format.averageBitrate || format.bitrate || 0,
      content_length: Number(format.contentLength || 0),
      url: format.url
    }))
    .filter((format) => format.url && format.mime_type?.startsWith('video/') && formatHasInlineAudio(format))
    .sort(compareVideoFormats);
}

export function rawPlayableVideoOnlyFormats(formats = []) {
  return formats
    .map((format) => ({
      itag: format.itag,
      mime_type: format.mimeType,
      quality_label: format.qualityLabel || format.quality || '',
      width: Number(format.width || 0),
      height: Number(format.height || 0),
      fps: Number(format.fps || 0),
      bitrate: format.bitrate || format.averageBitrate || 0,
      average_bitrate: format.averageBitrate || format.bitrate || 0,
      content_length: Number(format.contentLength || 0),
      url: format.url
    }))
    .filter((format) => format.url && format.mime_type?.startsWith('video/') && !formatHasInlineAudio(format))
    .sort(compareVideoFormats);
}

export function formatHasInlineAudio(format) {
  return /(?:mp4a|opus|vorbis)/i.test(format?.mime_type || '');
}

export function playbackAudioBitrate(stream = {}, mediaKind = 'audio') {
  const audioFormat = mediaKind === 'video' ? stream.audioFormat : stream.format;
  return Number(audioFormat?.bitrate || audioFormat?.average_bitrate || audioFormat?.averageBitrate || 0);
}

function formatHeight(format) {
  const height = Number(format?.height || 0);
  if (height > 0) return height;

  const label = format?.quality_label || format?.quality || format?.qualityLabel || '';
  const match = /(\d{3,4})p/i.exec(label);
  return match ? Number(match[1]) : 0;
}

function formatWidth(format) {
  return Number(format?.width || 0);
}

function formatFps(format) {
  return Number(format?.fps || 0);
}

function formatBitrate(format) {
  return Number(format?.bitrate || format?.average_bitrate || format?.averageBitrate || 0);
}

export function compareVideoFormats(left, right) {
  return formatHeight(right) - formatHeight(left) ||
    formatWidth(right) - formatWidth(left) ||
    formatFps(right) - formatFps(left) ||
    formatBitrate(right) - formatBitrate(left);
}

function formatMatchesMime(format, mime) {
  const [formatType] = format.mime_type.split(';');
  const [mimeType] = mime.split(';');
  if (formatType.trim() !== mimeType.trim()) return false;

  const formatCodec = /codecs="([^"]+)"/.exec(format.mime_type)?.[1];
  const mimeCodec = /codecs="([^"]+)"/.exec(mime)?.[1];
  return !mimeCodec || !formatCodec || formatCodec.includes(mimeCodec) || mimeCodec.includes(formatCodec);
}

export function chooseAudioFormatFromFormats(formats, supportedMimes = []) {
  if (!formats.length) return undefined;

  const browserSupportedMimes = supportedMimes.filter((item) => item.support);
  const preferredMimes = [
    ...browserSupportedMimes,
    ...(browserSupportedMimes.length ? [] : fallbackMimePreference.map((mimeType) => ({ mimeType, support: 'fallback' })))
  ];

  for (const { mimeType } of preferredMimes) {
    const matches = formats.filter((format) => formatMatchesMime(format, mimeType));
    if (matches.length) return matches[0];
  }

  if (supportedMimes.length) {
    throw new Error('No browser-supported audio format was returned by InnerTube');
  }

  return formats[0];
}

export function chooseVideoFormatFromFormats(formats, supportedMimes = [], options = {}) {
  const browserSupportedMimes = supportedMimes.filter((item) => item.support);
  const preferredMimes = [
    ...browserSupportedMimes,
    ...(browserSupportedMimes.length ? [] : fallbackVideoMimePreference.map((mimeType) => ({ mimeType, support: 'fallback' })))
  ];
  const matches = formats
    .filter((format) => preferredMimes.some(({ mimeType }) => formatMatchesMime(format, mimeType)))
    .sort(compareVideoFormats);
  if (matches.length) return matches[0];

  if (supportedMimes.length) {
    if (options.allowUnsupportedFallback) return null;
    throw new Error('No browser-supported video format was returned by InnerTube');
  }

  return formats[0];
}

export function chooseVideoOnlyFormatFromFormats(formats, supportedMimes = []) {
  return chooseVideoFormatFromFormats(formats, supportedMimes, { allowUnsupportedFallback: true });
}
