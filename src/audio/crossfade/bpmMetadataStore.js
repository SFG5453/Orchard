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

import { get, set } from 'idb-keyval';

const STORAGE_KEY = 'orchard:bpm-metadata:v1';

export function createBpmMetadataStorage() {
  return {
    async load() {
      try {
        const records = await get(STORAGE_KEY);
        return Array.isArray(records) ? records : [];
      } catch {
        return [];
      }
    },
    async save(records) {
      try {
        await set(STORAGE_KEY, records);
      } catch {
        // BPM metadata is an optimization; storage failures fall back to memory.
      }
    }
  };
}
