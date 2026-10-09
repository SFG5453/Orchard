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

// Video-only adaptive formats for the desktop theater. YouTube muxes audio and
// video only at 360p; above that the picture and soundtrack are separate files.

// H.264 decodes in hardware almost everywhere; VP9 covers heights H.264 lacks; AV1 is last.
function codecRank(mimeType = '') {
  if (/avc1/i.test(mimeType)) return 0;
  if (/vp0?9/i.test(mimeType)) return 1;
  if (/av01/i.test(mimeType)) return 3;
  return 2;
}

export function rawAdaptiveVideoFormats(formats = []) {
  return formats
    .map((format) => ({
      itag: format.itag,
      mime_type: format.mimeType || format.mime_type || '',
      height: Number(format.height || 0),
      fps: Number(format.fps || 0),
      bitrate: format.bitrate || format.averageBitrate || 0,
      content_length: Number(format.contentLength || format.content_length || 0),
      url: format.url,
      signatureCipher: format.signatureCipher,
      cipher: format.cipher
    }))
    .filter((format) => (format.url || format.signatureCipher || format.cipher) && format.height > 0 &&
      format.mime_type.startsWith('video/') && !/mp4a|opus|vorbis/i.test(format.mime_type));
}

// Distinct heights, tallest first, for the theater's quality menu.
export function availableHeights(formats = []) {
  return [...new Set(formats.map((format) => format.height))].sort((a, b) => b - a);
}

// Tallest height at or under `maxHeight` (0 means no cap), or the shortest when none fit.
export function chooseAdaptiveVideoFormat(formats = [], maxHeight = 0) {
  if (!formats.length) return null;
  const heights = availableHeights(formats);
  const height = heights.find((value) => !(maxHeight > 0) || value <= maxHeight) ?? heights[heights.length - 1];
  return formats
    .filter((format) => format.height === height)
    .sort((a, b) => codecRank(a.mime_type) - codecRank(b.mime_type) ||
      b.fps - a.fps || b.bitrate - a.bitrate)[0];
}
