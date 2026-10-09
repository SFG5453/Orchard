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

import { queryString } from '../shared/queryString.js';

// Fetches and normalizes the optional BetterLyrics provider response.
const betterLyricsBaseUrl = 'https://lyrics-api.boidu.dev/getLyrics';

export async function resolveBetterLyrics(metadata, { fetchWithTimeout, parseTtml }) {
  const query = queryString({
    s: metadata.title,
    a: metadata.artist,
    al: metadata.album,
    d: metadata.durationMs ? Math.round(metadata.durationMs / 1000) : undefined,
    videoId: metadata.videoId
  });

  const response = await fetchWithTimeout(`${betterLyricsBaseUrl}?${query}`);
  if (!response.ok) return null;

  const payload = await response.json();
  const ttml = typeof payload?.ttml === 'string'
    ? payload.ttml
    : typeof payload?.lyrics === 'string' ? payload.lyrics : '';
  return ttml ? parseTtml(ttml) : null;
}
