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

import { clampVolume } from './queuePersistence.js';

// One notch of a mouse wheel moves the volume this far. Five percent means a
// full sweep from silent to full is twenty notches -- coarse enough to be quick,
// fine enough to land on a specific level without fighting it.
export const VOLUME_WHEEL_STEP = 0.05;

/**
 * Turns a wheel event into a volume delta. Trackpads report fractional deltas in
 * both axes, so the dominant axis wins and only its sign is used: the step stays
 * constant regardless of how hard the surface was flicked.
 */
function wheelDirection(event) {
  const vertical = Number(event?.deltaY) || 0;
  const horizontal = Number(event?.deltaX) || 0;
  const dominant = Math.abs(vertical) >= Math.abs(horizontal) ? vertical : -horizontal;
  if (dominant === 0) return 0;
  // Wheel down (positive deltaY) lowers the volume, matching the slider's
  // left-to-right direction and every other player people already use.
  return dominant > 0 ? -1 : 1;
}

/**
 * Builds a wheel handler that nudges `volumeRef` by one step per notch.
 *
 * Callers bind it with `@wheel.prevent` so the surrounding view does not scroll
 * away under the pointer while the volume is being adjusted.
 */
export function createVolumeWheelHandler(volumeRef, step = VOLUME_WHEEL_STEP) {
  return (event) => {
    const direction = wheelDirection(event);
    if (!direction) return;
    const next = clampVolume(volumeRef.value) + (direction * step);
    // Snap back onto the step grid and then onto a thousandth, because
    // `steps * 0.05` alone still lands on values like 0.6000000000000001 and the
    // percentage readout would eventually drift off whole numbers.
    const snapped = Math.round(next / step) * step;
    volumeRef.value = clampVolume(Math.round(snapped * 1000) / 1000);
  };
}
