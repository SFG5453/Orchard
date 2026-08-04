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

function positiveSeconds(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : 0;
}

export function durationLabelSeconds(value) {
  const parts = String(value || '').trim().split(':').map(Number);
  if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
  return parts.reduce((total, part) => (total * 60) + part, 0);
}

function seekableEndSeconds(media) {
  const seekable = media?.seekable;
  if (!seekable?.length) return 0;
  try {
    return positiveSeconds(seekable.end(seekable.length - 1));
  } catch {
    return 0;
  }
}

export function reliablePlaybackDuration(ctx, media, track = ctx.activeTrack.value) {
  return Math.max(
    positiveSeconds(media?.duration),
    positiveSeconds(track?.durationSeconds),
    durationLabelSeconds(track?.duration),
    seekableEndSeconds(media)
  );
}

export async function resumeMediaAt(media, seconds) {
  const target = positiveSeconds(seconds);
  if (!media || !target) return;
  if (media.readyState === 0) {
    await new Promise((resolve) => {
      media.addEventListener('loadedmetadata', resolve, { once: true });
      media.addEventListener('error', resolve, { once: true });
    });
  }
  if (typeof media.fastSeek === 'function') media.fastSeek(target);
  else media.currentTime = target;
}
