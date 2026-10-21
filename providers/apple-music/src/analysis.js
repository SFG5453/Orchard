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

import { parseBrowseId } from './ids.js';

// A full Best Mix queue (50 songs plus the current one) fits one request; Apple accepted 51.
const BATCH = 50;

function catalogId(value) {
  const text = String(value || '');
  const parsed = parseBrowseId(text);
  if (parsed) return parsed.type === 'songs' ? parsed.id : '';
  return /^\d+$/.test(text) ? text : '';
}

export function createAnalysisCatalog(client) {
  /**
   * Apple's audio analysis (bpm, key, beat and bar grid, loudness, vocal activity) by catalog
   * song id. Only music.apple.com's API serves it. Songs Apple has not analysed are left out.
   */
  async function audioAnalysis(ids = []) {
    const wanted = [...new Set(ids.map(catalogId).filter(Boolean))];
    const items = {};
    for (let index = 0; index < wanted.length; index += BATCH) {
      const chunk = wanted.slice(index, index + BATCH);
      let result = null;
      try {
        result = await client.editorial(`/v1/catalog/${client.storefront()}/audio-analysis`, { ids: chunk.join(',') });
      } catch (error) {
        if (error?.code !== 'not_found') throw error;
      }
      for (const resource of result?.data || []) {
        if (resource?.id && resource.attributes) items[resource.id] = resource.attributes;
      }
    }
    return { items };
  }

  return { audioAnalysis };
}
