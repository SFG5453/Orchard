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


import { artworkUrl } from './normalize.js';

const ARTIST_STATION = /^am\.stations\.ra\.(\d+)$/;
const BATCH = 25;

// Composite covers blend two images on the CDN, which answers 404 when one is missing.
export function isCompositeCover(url) {
  return String(url || '').includes('imgRight=');
}

/** Replaces broken composite station covers with the station artist's own photo. */
export function createStationArtwork(client) {
  async function fill(cards, size = 600) {
    const broken = new Map();
    for (const card of cards || []) {
      const id = ARTIST_STATION.exec(card?.browseId || '')?.[1];
      if (id && isCompositeCover(card.thumbnail)) broken.set(card, id);
    }
    const ids = [...new Set(broken.values())];
    const photos = new Map();
    for (let start = 0; start < ids.length; start += BATCH) {
      try {
        const page = await client.get(`/v1/catalog/${client.storefront()}/artists`, { ids: ids.slice(start, start + BATCH).join(',') });
        for (const artist of page.data || []) photos.set(artist.id, artworkUrl(artist.attributes?.artwork, size));
      } catch {
        // Covers are cosmetic; the card keeps its own URL.
      }
    }
    for (const [card, id] of broken) {
      if (photos.get(id)) card.thumbnail = photos.get(id);
    }
    return cards;
  }

  return { fill };
}
