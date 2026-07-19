import assert from 'node:assert/strict';
import test from 'node:test';

import { createSmartCrossfadeAnalyzer } from '../src/audio/crossfade/smartCrossfadeAnalysis.js';

test('smart crossfade retries native availability after a transient failure', async () => {
  const stored = { analysisVersion: 5, duration: 240, mixOutTime: 190 };
  let availabilityChecks = 0;
  let decodeCalls = 0;
  const logs = [];
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: async () => {
      decodeCalls += 1;
      return null;
    },
    nativeBridge: {
      debug: async (event, details) => logs.push({ event, details }),
      available: async () => {
        availabilityChecks += 1;
        if (availabilityChecks === 1) throw new Error('IPC temporarily unavailable');
        return true;
      },
      get: async (trackId) => trackId === 'second-track' ? stored : null,
      analyze: async () => {
        throw new Error('Cached analysis should have been used');
      }
    }
  });

  try {
    assert.equal(await analyzer.analyze('first-track', 'first-url'), null);
    assert.deepEqual(await analyzer.analyze('second-track', 'second-url'), stored);
    assert.equal(availabilityChecks, 2);
    assert.equal(decodeCalls, 1);
    await new Promise((resolve) => setImmediate(resolve));
    assert.ok(logs.some((entry) => entry.event === 'availability-check-failed'));
    assert.ok(logs.some((entry) => entry.event === 'disk-cache-hit'));
  } finally {
    analyzer.destroy();
  }
});
