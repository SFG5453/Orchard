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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { AppleMusicError } from './client.js';
import { parseBrowseId } from './ids.js';

export function createMusicVideoCatalog(client) {
  /** Apple's music video for a catalog song as {videoId}, or {} when it has none. */
  async function musicVideoFor(id) {
    const parsed = parseBrowseId(id);
    const songId = parsed ? (parsed.type === 'songs' ? parsed.id : '') : String(id || '');
    // Library-only uploads (i.*) have no catalog relationships.
    if (!/^\d+$/.test(songId)) return {};
    let page;
    try {
      page = await client.get(`/v1/catalog/${client.storefront()}/songs/${songId}/music-videos`);
    } catch (error) {
      if (error instanceof AppleMusicError && error.status === 404) return {};
      throw error;
    }
    const video = (page?.data || []).find((entry) => entry.type === 'music-videos' && entry.id);
    return video ? { videoId: video.id } : {};
  }

  return { musicVideoFor };
}
