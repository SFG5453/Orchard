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
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { IPC_CHANNEL_VALUES } from '../shared/ipcChannels.js';

const preloadSource = await readFile(
  new URL('../electron/preload/index.cjs', import.meta.url),
  'utf8'
);

function channelLiterals(source) {
  return [...source.matchAll(/['"]([a-z][a-z0-9-]*:[a-z0-9-]+)['"]/g)]
    .map((match) => match[1]);
}

test('IPC channel values are unique', () => {
  assert.equal(new Set(IPC_CHANNEL_VALUES).size, IPC_CHANNEL_VALUES.length);
});

test('sandboxed preload mirrors the shared IPC contract', () => {
  const preloadChannels = [...new Set(channelLiterals(preloadSource))].sort();
  assert.deepEqual(preloadChannels, [...IPC_CHANNEL_VALUES].sort());
});
