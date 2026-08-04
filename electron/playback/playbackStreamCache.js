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
      requiresAuth: Boolean(opts.requiresAuth),
      lowPriority: Boolean(opts.lowPriority)
    };
  }

  function set(cacheKey, stream, opts = {}) {
    streams.set(cacheKey, stream);
    options.set(cacheKey, cacheableOptions(opts));
  }

  function cacheStream(videoId, cacheKey, stream, opts = {}) {
    const requestedItag = opts.itag ? String(opts.itag) : '';
    const resolvedItag = stream?.format?.itag ? String(stream.format.itag) : '';
    if (!requestedItag || requestedItag === resolvedItag) {
      set(cacheKey, stream, opts);
    }
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
