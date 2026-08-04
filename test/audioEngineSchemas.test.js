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
import { z } from 'zod';
import {
  audioEngineConfigSchema,
  parseAudioEngineProfile
} from '../src/audio/engine/audioEngineSchemas.js';

test('audio engine schemas keep Zod in strict-CSP jitless mode', () => {
  assert.equal(z.config().jitless, true);

  const config = audioEngineConfigSchema.parse({
    enabled: true,
    gains: Array.from({ length: 10 }, () => 0),
    outputDeviceId: 'default'
  });
  assert.equal(config.enabled, true);
  assert.equal(config.gains.length, 10);
});

test('jitless audio profile validation preserves the public profile contract', () => {
  const profile = parseAudioEngineProfile({
    app: 'orchard',
    type: 'audio-engine-profile',
    version: 1,
    config: { balance: 0 }
  });

  assert.equal(profile.config.balance, 0);
  assert.throws(
    () => parseAudioEngineProfile({ app: 'other', type: 'audio-engine-profile', version: 1, config: {} }),
    /not a valid Orchard audio profile/
  );
});
