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
  audioEngineConfigForCrossfadeMode,
  crossfadeModeForAudioEngine
} from '../src/audio/engine/audioFeatureCompatibility.js';

test('either EQ mode forces Smart Crossfade back to Standard', () => {
  assert.equal(crossfadeModeForAudioEngine('smart', { eqEnabled: true }), 'standard');
  assert.equal(crossfadeModeForAudioEngine('smart', { autoEqEnabled: true }), 'standard');
  assert.equal(crossfadeModeForAudioEngine('smart', {}), 'smart');
});

test('selecting Smart Crossfade disables both EQ modes', () => {
  const config = { enabled: true, autoEqEnabled: true, eqEnabled: true, outputGainDb: -2 };
  assert.deepEqual(audioEngineConfigForCrossfadeMode(config, 'smart'), {
    enabled: true,
    autoEqEnabled: false,
    eqEnabled: false,
    outputGainDb: -2
  });
  assert.deepEqual(audioEngineConfigForCrossfadeMode(config, 'standard'), config);
});
