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

import assert from 'node:assert/strict';
import test from 'node:test';
import { VOLUME_WHEEL_STEP, createVolumeWheelHandler } from '../src/app/playback/volumeWheel.js';

function volumeRef(value) {
  return { value };
}

test('wheel up raises and wheel down lowers by one step', () => {
  const volume = volumeRef(0.5);
  const onWheel = createVolumeWheelHandler(volume);

  onWheel({ deltaY: -120 });
  assert.equal(volume.value, 0.5 + VOLUME_WHEEL_STEP);

  onWheel({ deltaY: 120 });
  assert.equal(volume.value, 0.5);
});

test('step size is independent of how large the delta is', () => {
  const volume = volumeRef(0.5);
  const onWheel = createVolumeWheelHandler(volume);

  onWheel({ deltaY: -0.5 });
  assert.equal(volume.value, 0.55);

  onWheel({ deltaY: -4000 });
  assert.equal(volume.value, 0.6);
});

test('volume clamps at both ends', () => {
  const volume = volumeRef(0.02);
  const onWheel = createVolumeWheelHandler(volume);

  onWheel({ deltaY: 120 });
  assert.equal(volume.value, 0);
  onWheel({ deltaY: 120 });
  assert.equal(volume.value, 0);

  volume.value = 0.99;
  onWheel({ deltaY: -120 });
  assert.equal(volume.value, 1);
  onWheel({ deltaY: -120 });
  assert.equal(volume.value, 1);
});

test('a long run of notches leaves no floating point drift', () => {
  const volume = volumeRef(0);
  const onWheel = createVolumeWheelHandler(volume);

  for (let i = 0; i < 10; i += 1) onWheel({ deltaY: -120 });
  assert.equal(volume.value, 0.5);
  assert.equal(Math.round(volume.value * 100), 50);
});

test('horizontal trackpad scrolling follows the slider direction', () => {
  const volume = volumeRef(0.5);
  const onWheel = createVolumeWheelHandler(volume);

  onWheel({ deltaX: 12, deltaY: 0 });
  assert.equal(volume.value, 0.55);

  onWheel({ deltaX: -12, deltaY: 0 });
  assert.equal(volume.value, 0.5);
});

test('a wheel event with no movement leaves the volume alone', () => {
  const volume = volumeRef(0.42);
  const onWheel = createVolumeWheelHandler(volume);

  onWheel({ deltaX: 0, deltaY: 0 });
  assert.equal(volume.value, 0.42);
});
