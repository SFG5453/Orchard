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
    endTime
  };
}

async function requestSegments(videoId) {
  const url = new URL(apiEndpoint);
  url.searchParams.set('videoID', videoId);
  for (const category of playbackCategories) url.searchParams.append('category', category);

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), requestTimeoutMs);

  try {
    const response = await fetch(url, {
      headers: { accept: 'application/json' },
      signal: controller.signal
    });

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

  const segments = (await requestSegments(key))
    .map(normalizeSegment)
    .filter(Boolean)
    .sort((a, b) => a.startTime - b.startTime);

  segmentCache.set(key, segments);
  return segments;
}

