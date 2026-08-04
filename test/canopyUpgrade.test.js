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
import { canopyUpgradeAvailable } from '../src/app/core/readinessActions.js';

test('a 3.x listener is offered Canopy on any 4.x build, not only 4.0.0', () => {
  assert.equal(canopyUpgradeAvailable('3.2.3', '4.0.0-beta.4'), true);
  assert.equal(canopyUpgradeAvailable('3.0.0', '4.2.1'), true);
  assert.equal(canopyUpgradeAvailable('1.8.0', '4.1.0'), true);
});

test('staying inside a major version never re-offers the layout', () => {
  assert.equal(canopyUpgradeAvailable('4.0.0', '4.1.0'), false);
  assert.equal(canopyUpgradeAvailable('3.2.2', '3.2.3'), false);
  assert.equal(canopyUpgradeAvailable('4.1.0', '4.1.0'), false);
});

test('a first run has no previous version to compare and stays quiet', () => {
  assert.equal(canopyUpgradeAvailable('', '4.0.0'), false);
  assert.equal(canopyUpgradeAvailable(undefined, '4.0.0'), false);
  assert.equal(canopyUpgradeAvailable('not-a-version', '4.0.0'), false);
});

test('a downgrade back into 3.x is not an upgrade', () => {
  assert.equal(canopyUpgradeAvailable('4.0.0', '3.2.3'), false);
});
