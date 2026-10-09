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

// Reads community-submitted non-music markers from SponsorBlock so playback can
// offer a skip affordance and keep lyric timing aligned with the music.
// Talks to the public API directly rather than pulling in `sponsorblock-api`.
//
// `music_offtopic` is the category the SponsorBlock extension uses for its
// "Non-Music" skipping on YouTube Music, and it carries nearly all of the
// submissions on music videos -- intro/outro alone leave most tracks empty.

const apiEndpoint = 'https://sponsor.ajay.app/api/skipSegments';
const playbackCategories = ['music_offtopic', 'intro', 'outro'];
// Only this category is "not music". Intro/outro marks can sit on real playing music,
// and skipping those would eat the instrumental bits people actually came for.
const nonMusicCategories = ['music_offtopic'];
// Music videos also mark channel segments: sponsors, self-promotion and subscribe reminders.
const videoCategories = ['music_offtopic', 'sponsor', 'selfpromo', 'interaction'];
// SponsorBlock stores the video length each segment was timed against. A re-upload that
// differs by more than this has shifted timestamps, so its segments cannot be trusted.
const durationToleranceSeconds = 2;
const requestTimeoutMs = 6000;
const segmentCache = new Map();

function normalizeSegment(segment) {
  const [rawStart, rawEnd] = Array.isArray(segment?.segment) ? segment.segment : [];
  const startTime = Number(rawStart);
  const endTime = Number(rawEnd);

  if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || endTime <= startTime) {
    return null;
  }

  return {
    id: segment.UUID || `${startTime}-${endTime}`,
    category: segment.category || 'unknown',
    startTime,
    endTime,
    videoDuration: Number(segment.videoDuration) || 0
  };
}

// Runs in bare QuickJS: no URL, URLSearchParams or abortable fetch (see lyricsResolver.js),
// so the query is built by hand and a slow server is abandoned instead of cancelled.
async function requestSegments(videoId, categories) {
  const query = [
    `videoID=${encodeURIComponent(videoId)}`,
    ...categories.map((category) => `category=${encodeURIComponent(category)}`)
  ].join('&');

  let timer;
  const expired = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('SponsorBlock request timed out.')), requestTimeoutMs);
  });

  try {
    const response = await Promise.race([
      fetch(`${apiEndpoint}?${query}`, { headers: { accept: 'application/json' } }),
      expired
    ]);

    // SponsorBlock answers 404 when nobody has submitted segments for a video.
    if (response.status === 404) return [];
    if (!response.ok) throw new Error(`SponsorBlock request failed (${response.status})`);

    const payload = await response.json();
    return Array.isArray(payload) ? payload : [];
  } finally {
    clearTimeout(timer);
  }
}

export async function getPlaybackSegments(videoId) {
  const key = String(videoId || '').trim();
  if (!key) return [];

  if (segmentCache.has(key)) return segmentCache.get(key);

  const segments = (await requestSegments(key, playbackCategories))
    .map(normalizeSegment)
    .filter(Boolean)
    .sort((a, b) => a.startTime - b.startTime);

  segmentCache.set(key, segments);
  return segments;
}

// Non-music spans of the stream that is actually playing, safe to seek over.
// `durationSeconds` is the length of that stream. Segments timed against a different
// cut are dropped, which also keeps lyrics lined up: a skip is a plain seek on the
// stream's own clock, so lyric timing never drifts, but only if the clock is the same.
// Never throws; SponsorBlock being down must not take playback down with it (it has
// one job and no excuse).
export async function getNonMusicSegments(videoId, durationSeconds = 0, options = {}) {
  const key = String(videoId || '').trim();
  if (!key) return [];

  const categories = options.video ? videoCategories : nonMusicCategories;
  const cacheKey = `${options.video ? 'video' : 'non-music'}:${key}`;
  let all = segmentCache.get(cacheKey);
  if (!all) {
    try {
      all = (await requestSegments(key, categories))
        .map(normalizeSegment)
        .filter(Boolean);
    } catch {
      return [];
    }
    segmentCache.set(cacheKey, all);
  }

  const duration = Number(durationSeconds) || 0;
  const matching = all.filter((segment) =>
    !(duration > 0 && segment.videoDuration > 0) ||
    Math.abs(segment.videoDuration - duration) <= durationToleranceSeconds);
  return mergeSegments(matching, duration);
}

// Overlapping or touching submissions become one span, so a skip never lands inside
// another one. Spans are clamped to the stream so the end never seeks past the file.
function mergeSegments(segments, duration) {
  const merged = [];
  for (const segment of [...segments].sort((a, b) => a.startTime - b.startTime)) {
    const endTime = duration > 0 ? Math.min(segment.endTime, duration) : segment.endTime;
    if (endTime <= segment.startTime) continue;
    const last = merged[merged.length - 1];
    if (last && segment.startTime <= last.endTime + 0.25) {
      last.endTime = Math.max(last.endTime, endTime);
    } else {
      merged.push({ id: segment.id, category: segment.category, startTime: segment.startTime, endTime });
    }
  }
  return merged;
}

