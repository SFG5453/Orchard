import assert from 'node:assert/strict';
import test from 'node:test';

import { createSmartCrossfadeAnalyzer } from '../src/audio/crossfade/smartCrossfadeAnalysis.js';

function validAnalysis(overrides = {}) {
  return {
    analysisVersion: 7,
    duration: 120,
    bpm: 120,
    beatInterval: 0.5,
    beatConfidence: 0.8,
    beats: [0, 0.5, 1],
    downbeats: [0],
    phraseBoundaries: [0, 16],
    mixInTime: 16,
    mixOutTime: 112,
    ...overrides
  };
}

function audioBuffer(duration = 120) {
  const samples = new Float32Array([0.1, -0.1, 0.2, -0.2]);
  return {
    duration,
    sampleRate: 11025,
    numberOfChannels: 1,
    getChannelData: () => samples
  };
}

function workerFactory(result = validAnalysis()) {
  return () => ({
    onmessage: null,
    onerror: null,
    postMessage(message) {
      queueMicrotask(() => {
        if (message.prepareOnly) {
          this.onmessage?.({ data: {
            id: message.id,
            prepared: {
              samples: new Float32Array([0.1, -0.1]).buffer,
              sampleRate: 11025,
              duration: message.duration
            }
          } });
          return;
        }
        const value = typeof result === 'function' ? result(message) : result;
        this.onmessage?.({ data: { id: message.id, result: value } });
      });
    },
    terminate() {}
  });
}

function bridge(overrides = {}) {
  return {
    available: async () => false,
    get: async () => null,
    store: async () => true,
    analyze: async () => validAnalysis(),
    debug: async () => {},
    ...overrides
  };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));

test('smart crossfade reads persistent cache before checking native availability', async () => {
  const stored = {
    analysisVersion: 7,
    duration: 240,
    bpm: 120,
    beatInterval: 0.5,
    beats: [0, 0.5],
    downbeats: [0],
    phraseBoundaries: [0, 16],
    mixOutTime: 190
  };
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
    await assert.rejects(analyzer.analyze('first-track', 'first-url'), /no buffer/i);
    const cached = await analyzer.analyze('second-track', 'second-url');
    assert.equal(cached.bpm, stored.bpm);
    assert.equal(cached.bpmSource, 'cache');
    assert.equal(availabilityChecks, 0);
    assert.equal(decodeCalls, 1);
    await new Promise((resolve) => setImmediate(resolve));
    assert.ok(logs.some((entry) => entry.event === 'disk-cache-hit'));
  } finally {
    analyzer.destroy();
  }
});

test('local cache hits avoid stream resolution and decoding even without the native addon', async () => {
  let decodeCalls = 0;
  let resolveCalls = 0;
  let availabilityCalls = 0;
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: async () => {
      decodeCalls += 1;
      return audioBuffer();
    },
    nativeBridge: bridge({
      available: async () => {
        availabilityCalls += 1;
        return false;
      },
      get: async () => validAnalysis({ analysisSource: 'local-worker' })
    })
  });

  try {
    const result = await analyzer.analyze('cached', async () => {
      resolveCalls += 1;
      return 'stream-url';
    });
    assert.equal(result.bpm, 120);
    assert.equal(result.bpmSource, 'cache');
    assert.equal(result.cachedBpmSource, 'local-worker');
    assert.equal(resolveCalls, 0);
    assert.equal(decodeCalls, 0);
    assert.equal(availabilityCalls, 0);
  } finally {
    analyzer.destroy();
  }
});

test('duplicate requests share one uncached preparation job', async () => {
  let decodeCalls = 0;
  let releaseDecode;
  const decodeGate = new Promise((resolve) => { releaseDecode = resolve; });
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: async () => {
      decodeCalls += 1;
      await decodeGate;
      return audioBuffer();
    },
    nativeBridge: bridge(),
    workerFactory: workerFactory()
  });

  try {
    const first = analyzer.analyze('same-track', 'first-url');
    const second = analyzer.analyze('same-track', 'second-url');
    await tick();
    assert.equal(decodeCalls, 1);
    releaseDecode();
    const [left, right] = await Promise.all([first, second]);
    assert.strictEqual(left, right);
  } finally {
    analyzer.destroy();
  }
});

test('no more than four uncached preparation jobs run concurrently', async () => {
  let active = 0;
  let maximumActive = 0;
  const releases = new Map();
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: (url) => new Promise((resolve) => {
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      releases.set(url, () => {
        releases.delete(url);
        active -= 1;
        resolve(audioBuffer());
      });
    }),
    nativeBridge: bridge(),
    workerFactory: workerFactory()
  });

  try {
    const requests = Array.from({ length: 6 }, (_, index) =>
      analyzer.analyze(`track-${index}`, `url-${index}`, { priority: 2 })
    );
    await tick();
    assert.equal(releases.size, 4);
    assert.equal(maximumActive, 4);
    releases.get('url-0')();
    await tick();
    assert.equal(releases.has('url-4'), true);
    releases.get('url-1')();
    await tick();
    assert.equal(releases.has('url-5'), true);
    ['url-2', 'url-3', 'url-4', 'url-5'].forEach((url) => releases.get(url)());
    await Promise.all(requests);
    assert.equal(maximumActive, 4);
  } finally {
    analyzer.destroy();
  }
});

test('queued current and next tracks outrank background analysis', async () => {
  const order = [];
  const releases = new Map();
  const analyzer = createSmartCrossfadeAnalyzer({
    maxActiveJobs: 1,
    decodeAudio: (url) => new Promise((resolve) => {
      order.push(url);
      releases.set(url, () => resolve(audioBuffer()));
    }),
    nativeBridge: bridge(),
    workerFactory: workerFactory()
  });

  try {
    const backgroundOne = analyzer.analyze('background-1', 'background-1', { priority: 2 });
    await tick();
    const backgroundTwo = analyzer.analyze('background-2', 'background-2', { priority: 2 });
    const next = analyzer.analyze('next', 'next', { priority: 1 });
    const current = analyzer.analyze('current', 'current', { priority: 0 });
    await tick();

    releases.get('background-1')();
    await tick();
    assert.deepEqual(order, ['background-1', 'current']);
    releases.get('current')();
    await tick();
    assert.deepEqual(order, ['background-1', 'current', 'next']);
    releases.get('next')();
    await tick();
    assert.deepEqual(order, ['background-1', 'current', 'next', 'background-2']);
    releases.get('background-2')();
    await Promise.all([backgroundOne, backgroundTwo, next, current]);
  } finally {
    analyzer.destroy();
  }
});

test('one caller aborting does not discard a shared result needed by another caller', async () => {
  let releaseDecode;
  let stored = 0;
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: () => new Promise((resolve) => { releaseDecode = () => resolve(audioBuffer()); }),
    nativeBridge: bridge({ store: async () => { stored += 1; } }),
    workerFactory: workerFactory()
  });
  const controller = new AbortController();

  try {
    const cancelled = analyzer.analyze('shared', 'url', { signal: controller.signal });
    const needed = analyzer.analyze('shared', 'url');
    await tick();
    controller.abort();
    await assert.rejects(cancelled, { name: 'AbortError' });
    releaseDecode();
    assert.equal((await needed).bpm, 120);
    assert.equal(stored, 1);
  } finally {
    analyzer.destroy();
  }
});

test('invalid local BPM is rejected and never persisted', async () => {
  let stored = 0;
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: async () => audioBuffer(),
    nativeBridge: bridge({ store: async () => { stored += 1; } }),
    workerFactory: workerFactory(validAnalysis({ bpm: Number.NaN, beatInterval: 0 }))
  });

  try {
    await assert.rejects(analyzer.analyze('invalid', 'url'), /invalid BPM/i);
    assert.equal(stored, 0);
  } finally {
    analyzer.destroy();
  }
});

test('native analysis failure falls back to the worker and persists that result', async () => {
  const stored = [];
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: async () => audioBuffer(),
    nativeBridge: bridge({
      available: async () => true,
      analyze: async () => { throw new Error('native DSP failed'); },
      store: async (trackId, result) => stored.push({ trackId, result })
    }),
    workerFactory: workerFactory()
  });

  try {
    const result = await analyzer.analyze('fallback', 'url');
    assert.equal(result.bpmSource, 'local-worker');
    assert.equal(stored.length, 1);
    assert.equal(stored[0].trackId, 'fallback');
  } finally {
    analyzer.destroy();
  }
});

test('transient upstream failures use bounded delayed retries instead of an immediate loop', async () => {
  const attempts = [];
  const analyzer = createSmartCrossfadeAnalyzer({
    decodeAudio: async () => {
      attempts.push(Date.now());
      throw new Error('Audio analysis fetch failed with HTTP 503');
    },
    nativeBridge: bridge(),
    retryBaseMs: 10,
    retryMaxMs: 20,
    random: () => 0
  });

  try {
    await assert.rejects(analyzer.analyze('failing', 'url'), /HTTP 503/);
    assert.equal(attempts.length, 3);
    assert.ok(attempts[1] - attempts[0] >= 7);
    assert.ok(attempts[2] - attempts[1] >= 14);
  } finally {
    analyzer.destroy();
  }
});

test('analysis diagnostics redact credentials and signed stream queries', async () => {
  const logs = [];
  const analyzer = createSmartCrossfadeAnalyzer({
    nativeBridge: bridge({ debug: async (event, details) => logs.push({ event, details }) })
  });
  try {
    analyzer.report('safe-log', {
      authorization: 'Bearer secret',
      cookie: 'SID=secret',
      errorMessage: 'Failed https://example.test/audio?signature=secret&token=private'
    });
    await tick();
    const serialized = JSON.stringify(logs);
    assert.doesNotMatch(serialized, /Bearer secret|SID=secret|signature=secret|token=private/);
    assert.match(serialized, /redacted/);
  } finally {
    analyzer.destroy();
  }
});
