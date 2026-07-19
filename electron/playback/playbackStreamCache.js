// Keeps resolved playback URLs and option-specific selections bounded to the main process.
export function createPlaybackStreamCache() {
  const streams = new Map();
  const options = new Map();

  function key(videoId, opts = {}) {
    const mediaKind = opts.mediaKind === 'video' ? 'video' : 'audio';
    return `${videoId}:${mediaKind}:${opts.itag || 'auto'}`;
  }

  function cacheableOptions(opts = {}) {
    return {
      mediaKind: opts.mediaKind,
      supportedMimes: opts.supportedMimes || [],
      supportedAudioMimes: opts.supportedAudioMimes || [],
      avoidItags: opts.avoidItags || [],
      avoidMimeTypes: opts.avoidMimeTypes || [],
      preferInlineVideo: Boolean(opts.preferInlineVideo),
      lowPriority: Boolean(opts.lowPriority),
      refreshStream: Boolean(opts.refreshStream)
    };
  }

  function set(cacheKey, stream, opts = {}) {
    streams.set(cacheKey, stream);
    options.set(cacheKey, cacheableOptions(opts));
  }

  function cacheStream(videoId, cacheKey, stream, opts = {}) {
    set(cacheKey, stream, opts);
    set(key(videoId, { ...opts, itag: stream.format.itag }), stream, opts);

    if (!stream.audioUrl || !stream.audioFormat) return;
    set(key(videoId, { mediaKind: 'audio', itag: stream.audioFormat.itag }), {
      url: stream.audioUrl,
      format: stream.audioFormat,
      mediaKind: 'audio',
      cacheMetadata: stream.cacheMetadata,
      expiresAt: stream.expiresAt
    }, { ...opts, mediaKind: 'audio' });
  }

  function deleteKey(cacheKey) {
    streams.delete(cacheKey);
    options.delete(cacheKey);
  }

  return {
    cacheStream,
    deleteKey,
    getOptions: (cacheKey) => options.get(cacheKey),
    getStream: (cacheKey) => streams.get(cacheKey),
    key
  };
}
