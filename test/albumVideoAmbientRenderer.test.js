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
import {
  albumAmbientSpriteLayout,
  albumSeamSpriteLayout
} from '../src/app/appearance/albumVideoAmbientRenderer.js';

test('album video ambient layout fills the left field with a centered overscan', () => {
  const layout = albumAmbientSpriteLayout(1340, 456, 768, 768);
  assert.ok(Math.abs(layout.x - 442.2) < 1e-9);
  assert.equal(layout.y, 228);
  assert.ok(Math.abs(layout.scale - 1.3609375) < 1e-9);
});

test('album video ambient layout remains finite for incomplete dimensions', () => {
  const layout = albumAmbientSpriteLayout(0, 0, 0, 0);
  assert.deepEqual(layout, { scale: 1.5, x: 0.33, y: 0.5 });
});

test('album seam layout places the mirrored frame exactly behind the DOM video', () => {
  const layout = albumSeamSpriteLayout(
    { left: 152, top: 12 },
    { left: 346, top: 85, width: 454, height: 454 },
    768,
    768
  );
  assert.deepEqual(layout, {
    scaleX: 454 / 768,
    scaleY: 454 / 768,
    x: 421,
    y: 300
  });
});
