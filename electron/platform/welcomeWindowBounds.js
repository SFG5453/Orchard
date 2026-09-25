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

const DISPLAY_MARGIN = 48;
const PREFERRED_WIDTH = 1180;
const PREFERRED_HEIGHT = 820;
const MIN_WIDTH = 640;
const MIN_HEIGHT = 560;

function availableSize(value, preferred) {
  const displaySize = Number(value);
  if (!Number.isFinite(displaySize) || displaySize <= DISPLAY_MARGIN) return preferred;
  return Math.min(preferred, Math.floor(displaySize) - DISPLAY_MARGIN);
}

export function welcomeWindowBounds(workAreaSize = {}) {
  const width = availableSize(workAreaSize.width, PREFERRED_WIDTH);
  const height = availableSize(workAreaSize.height, PREFERRED_HEIGHT);

  return {
    width,
    height,
    minWidth: Math.min(MIN_WIDTH, width),
    minHeight: Math.min(MIN_HEIGHT, height)
  };
}
