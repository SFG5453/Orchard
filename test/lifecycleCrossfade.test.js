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

import { disableCrossfadePlayback } from '../src/app/core/lifecycle.js';

test('disabling crossfade cancels both transition engines before restoring volume', () => {
  const calls = [];
  const ctx = {
    autoCrossfade: { cancel: () => calls.push('auto') },
    dismissSmartCrossfadeMix: () => calls.push('dismiss'),
    setCurrentAudioVolume: () => calls.push('volume'),
    stopCrossfadeClock: () => calls.push('clock'),
    wsolaCrossfade: { cancel: () => calls.push('wsola') }
  };

  disableCrossfadePlayback(ctx);

  assert.deepEqual(calls, ['clock', 'auto', 'wsola', 'dismiss', 'volume']);
});
