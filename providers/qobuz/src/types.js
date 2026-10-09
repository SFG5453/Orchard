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

export const QOBUZ_BASE_URL = 'https://www.qobuz.com/api.json/0.2';
export const QOBUZ_PLAY_URL = 'https://play.qobuz.com';
export const QOBUZ_PARTITION = 'persist:qobuz';
export const QOBUZ_USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';

export const QOBUZ_AUDIO_QUALITIES = Object.freeze(['auto', 'lossless', 'hires']);
export const QOBUZ_FORMAT_IDS = Object.freeze({
  auto: 27,
  lossless: 6,
  hires: 27
});

export function normalizeQobuzQuality(value) {
  return QOBUZ_AUDIO_QUALITIES.includes(value) ? value : 'auto';
}

export function canonicalTrack(input = {}) {
  const artists = Array.isArray(input.artists)
    ? input.artists.map((artist) => String(artist || '').trim()).filter(Boolean)
    : [];
  const primaryArtist = String(input.artist || artists[0] || '').trim();
  if (primaryArtist && !artists.length) artists.push(primaryArtist);

  return {
    title: String(input.title || '').trim(),
    artists,
    album: String(input.album || '').trim(),
    durationMs: Math.max(0, Math.round(Number(input.durationMs || Number(input.durationSeconds || 0) * 1000) || 0)),
    isrc: String(input.isrc || '').trim().toUpperCase(),
    explicit: input.explicit === true
  };
}
