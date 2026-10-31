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

import { createPlaybackProviderCoordinator } from '../src/playback/providerCoordinator.js';

test('provider coordination uses opaque sources without an HTTP proxy', async () => {
  const calls = [];
  const coordinator = createPlaybackProviderCoordinator({
    providers: [{
      id: 'qobuz',
      enabled: () => true,
      quality: () => 'lossless',
      matchTrack: async () => ({ id: 42 }),
      resolveStream: async () => ({ playbackId: 'opaque' }),
      readRange: async (...args) => {
        calls.push(args);
        return { bytes: Uint8Array.of(1, 2, 3) };
      }
    }]
  });

  assert.deepEqual(await coordinator.resolve({ title: 'Track' }), {
    provider: 'qobuz',
    match: { id: 42 },
    source: { playbackId: 'opaque' }
  });
  assert.deepEqual((await coordinator.readRange('qobuz', 'opaque', { start: 2 })).bytes, Uint8Array.of(1, 2, 3));
  assert.deepEqual(calls, [['opaque', { start: 2 }]]);
});

