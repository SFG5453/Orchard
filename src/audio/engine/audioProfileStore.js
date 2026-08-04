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
import { learnedAudioProfilesSchema } from './audioEngineSchemas.js';

const PROFILE_STORAGE_KEY = 'orchard:auto-eq-profiles:v1';
const MAX_PROFILES = 120;

export async function loadLearnedAudioProfiles() {
  try {
    const result = learnedAudioProfilesSchema.safeParse(await get(PROFILE_STORAGE_KEY));
    return result.success ? result.data : [];
  } catch {
    return [];
  }
}

export async function saveLearnedAudioProfiles(profiles) {
  const valid = learnedAudioProfilesSchema.parse(profiles);
  await set(PROFILE_STORAGE_KEY, valid.slice(-MAX_PROFILES));
}
