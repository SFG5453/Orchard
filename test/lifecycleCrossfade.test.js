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
