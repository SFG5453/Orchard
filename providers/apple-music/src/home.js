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

import { AppleMusicError } from './client.js';
import { APPLE_MUSIC_SOURCE } from './ids.js';
import { ARTIST_INCLUDES, items } from './normalize.js';

// Apple's daily and weekly personal playlists (Your Essentials, New Music, Chill...).
export const PERSONAL_MIXES_KEY = 'apple-personal-mixes';

function isPersonalMixShelf(resources) {
  return resources.length > 0 && resources.every((entry) => entry.attributes?.playlistType === 'personal-mix');
}

function titleOf(value) {
  return typeof value === 'string' ? value : value?.stringForDisplay || '';
}

export function createHomeCatalog(client) {
  async function recommendationSections(resources, seen = new Set()) {
    const sections = [];
    for (const resource of resources) {
      if (!resource.id || seen.has(resource.id)) continue;
      seen.add(resource.id);
      const contents = resource.relationships?.contents;
      const children = [...(resource.relationships?.recommendations?.data || [])];
      const cards = items(contents?.data);
      if (cards.length) {
        sections.push({
          key: isPersonalMixShelf(contents.data) ? PERSONAL_MIXES_KEY : `apple-recommendation-${resource.id}`,
          title: titleOf(resource.attributes?.title) || 'For You',
          items: cards,
          browsePayload: contents.next ? { browseId: `am.recommendations.${resource.id}`, params: '' } : null
        });
      }
      children.push(...(contents?.data || []).filter((entry) => entry.type === 'personal-recommendation'));
      sections.push(...await recommendationSections(children, seen));
    }
    return sections;
  }

  async function charts() {
    const response = await client.get(`/v1/catalog/${client.storefront()}/charts`, { types: 'songs,albums,playlists', limit: 25, ...ARTIST_INCLUDES });
    const sections = [];
    for (const [type, entries] of Object.entries(response.results || {})) {
      for (const chart of entries) {
        const cards = items(chart.data);
        if (cards.length) sections.push({ key: `apple-chart-${type}-${chart.chart || 'most-played'}`, title: chart.name || `Top ${type}`, items: cards });
      }
    }
    return sections;
  }

  async function home() {
    const response = await client.all('/v1/me/recommendations', { include: 'contents', limit: 30, ...ARTIST_INCLUDES });
    const sections = await recommendationSections(response.items);
    if (!sections.length) sections.push(...await charts());
    if (!sections.length) throw new AppleMusicError(0, 'empty_home', 'Apple Music has no recommendations available.');
    return { source: APPLE_MUSIC_SOURCE, sections, artists: [] };
  }

  async function homeSection(id) {
    const prefix = 'am.recommendations.';
    if (!String(id).startsWith(prefix) || !String(id).slice(prefix.length)) return [];
    const response = await client.all(`/v1/me/recommendations/${encodeURIComponent(id.slice(prefix.length))}/contents`, ARTIST_INCLUDES);
    return items(response.items);
  }

  return { home, homeSection };
}
