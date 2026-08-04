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

export const GRAPHICS_MODES = Object.freeze({
  AUTOMATIC: 'automatic',
  INTEGRATED: 'integrated'
});

export const GRAPHICS_MODE_OPTIONS = Object.freeze([
  Object.freeze({ label: 'Automatic', value: GRAPHICS_MODES.AUTOMATIC }),
  Object.freeze({ label: 'Integrated GPU', value: GRAPHICS_MODES.INTEGRATED })
]);

export function normalizeGraphicsMode(value) {
  return GRAPHICS_MODE_OPTIONS.some((option) => option.value === value)
    ? value
    : GRAPHICS_MODES.AUTOMATIC;
}

export function integratedGpuSelectionSupported(platform = process.platform) {
  return ['darwin', 'win32'].includes(platform);
}
