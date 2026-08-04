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

export async function fetchArtistArtFromAudioDB(ctx, artistName) {
  if (!artistName) return;

  try {
    const url = `https://www.theaudiodb.com/api/v1/json/2/search.php?s=${encodeURIComponent(artistName)}`;
    const response = await fetch(url);
    if (!response.ok) return;

    const data = await response.json();
    const artist = data?.artists?.[0];

    // Pick the best available wide artwork, preferring fanart and banner
    const highResArtwork = artist?.strArtistFanart || artist?.strArtistFanart2 || artist?.strArtistFanart3 || artist?.strArtistBanner;

    if (highResArtwork) {
      // Ensure we are still viewing the same artist we fetched data for
      if (ctx.browseDetail.value && ctx.browseDetail.value.title === artistName) {
        ctx.browseDetail.value = {
          ...ctx.browseDetail.value,
          customProfileArtwork: highResArtwork
        };
      }
    }
  } catch (error) {
    console.warn(`Failed to fetch high-res artwork from AudioDB for ${artistName}:`, error);
  }
}
