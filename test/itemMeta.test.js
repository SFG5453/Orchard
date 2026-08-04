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
import { installVisualUtils } from '../src/app/appearance/visualUtils.js';

function ctxWithVisualUtils() {
  const ctx = {};
  installVisualUtils(ctx);
  return ctx;
}

test('itemMeta falls back to the album artist when a track subtitle is just a year', () => {
  const ctx = ctxWithVisualUtils();
  const track = { type: 'track', subtitle: '2016', artists: [] };

  assert.equal(ctx.itemMeta(track, 'Bruno Mars'), 'Bruno Mars');
});

test('itemMeta still returns a real subtitle for a track', () => {
  const ctx = ctxWithVisualUtils();
  const track = { type: 'track', subtitle: 'Bruno Mars', artists: [] };

  assert.equal(ctx.itemMeta(track, 'Fallback Artist'), 'Bruno Mars');
});

test('itemMeta prefers artists over subtitle', () => {
  const ctx = ctxWithVisualUtils();
  const track = { type: 'track', subtitle: '2016', artists: ['Bruno Mars'] };

  assert.equal(ctx.itemMeta(track, 'Fallback Artist'), 'Bruno Mars');
});
