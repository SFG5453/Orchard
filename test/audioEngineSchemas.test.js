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
