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

import assert from 'node:assert/strict';
import test from 'node:test';

import { welcomeWindowBounds } from '../electron/platform/welcomeWindowBounds.js';

test('welcome window uses its intended canvas when the display has room', () => {
  assert.deepEqual(welcomeWindowBounds({ width: 1920, height: 1080 }), {
    width: 1180,
    height: 820,
    minWidth: 640,
    minHeight: 560
  });
});

test('welcome window leaves a margin and reachable minimum on smaller displays', () => {
  const bounds = welcomeWindowBounds({ width: 1280, height: 720 });

  assert.deepEqual(bounds, {
    width: 1180,
    height: 672,
    minWidth: 640,
    minHeight: 560
  });
  assert.ok(bounds.width <= 1280 - 48);
  assert.ok(bounds.height <= 720 - 48);
  assert.ok(bounds.minWidth <= bounds.width);
  assert.ok(bounds.minHeight <= bounds.height);
});

test('welcome window constraints never exceed a very small work area', () => {
  const bounds = welcomeWindowBounds({ width: 560, height: 440 });

  assert.deepEqual(bounds, {
    width: 512,
    height: 392,
    minWidth: 512,
    minHeight: 392
  });
});
