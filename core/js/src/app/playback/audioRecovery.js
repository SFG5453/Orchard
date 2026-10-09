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

const maxFailedAudioFormats = 4;
const maxFailedVideoFormats = 4;

function arraySet(values = []) {
  return new Set(values.map(String).filter(Boolean));
}

function mimeFamily(value = '') {
  return String(value).split(';', 1)[0].trim().toLowerCase();
}

export function audioRecoveryPlan(track, options = {}) {
  if (!track || track.mediaKind === 'video' || (!options.refreshStream && !track.itag)) return null;

  const failedItags = arraySet(track.failedAudioItags);
  const failedMimeTypes = arraySet(track.failedAudioMimeTypes);
  if (options.avoidCurrentFormat) {
    if (track.itag) failedItags.add(String(track.itag));
  }
  if (options.avoidCurrentMimeType) {
    const failedMimeFamily = mimeFamily(track.mimeType);
    if (failedMimeFamily) failedMimeTypes.add(failedMimeFamily);
  }
  if (options.avoidCurrentFormat && failedItags.size > maxFailedAudioFormats) return null;

  return {
    track: {
      ...track,
      failedAudioItags: [...failedItags],
      failedAudioMimeTypes: [...failedMimeTypes],
      playbackFallbackTried: failedItags.size > 0,
      streamRefreshTried: options.refreshStream ? true : track.streamRefreshTried
    },
    avoidItags: [...failedItags],
    avoidMimeTypes: [...failedMimeTypes]
  };
}

export function videoRecoveryPlan(track, options = {}) {
  if (!track || track.mediaKind !== 'video' || (!options.refreshStream && !track.itag)) return null;

  const failedItags = arraySet(track.failedVideoItags);
  if (options.avoidCurrentFormat && track.itag) failedItags.add(String(track.itag));
  if (options.avoidCurrentFormat && failedItags.size > maxFailedVideoFormats) return null;

  return {
    track: {
      ...track,
      failedVideoItags: [...failedItags],
      playbackFallbackTried: failedItags.size > 0,
      streamRefreshTried: options.refreshStream ? true : track.streamRefreshTried
    },
    avoidItags: [...failedItags]
  };
}
