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

export const SONG_CACHE_DEFAULTS = {
  enabled: true,
  maxSizeMb: 512
};

export function clampSongCacheMaxSizeMb(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return SONG_CACHE_DEFAULTS.maxSizeMb;
  return Math.min(4096, Math.max(128, Math.round(numeric / 128) * 128));
}
