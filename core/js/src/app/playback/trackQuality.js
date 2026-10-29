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

/*
 * Tracks carry bitrate in bits/s from InnerTube but in kbps once a cached copy
 * has been re-probed locally, so normalise both to whole kbps. Returns '' when
 * there is nothing worth showing, which is the marker's v-if.
 */
export function bitrateLabel(track) {
  const value = Number(track?.bitrate || 0);
  if (!Number.isFinite(value) || value <= 0) return '';
  return String(Math.round(value >= 1000 ? value / 1000 : value));
}
