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
  createWsolaCrossfade,
  wsolaProcessingCompatible
} from '../src/audio/crossfade/wsolaCrossfade.js';

function fakeClock() {
  const timers = new Map();
  let nextId = 0;
  const window = {
    clearTimeout: (id) => timers.delete(id),
    setTimeout: (callback, delay) => {
      const id = ++nextId;
      timers.set(id, { callback, delay });
      return id;
    }
  };
  return {
    window,
    pending: () => timers.size,
    async runNext() {
      const [id, timer] = [...timers].sort((left, right) => left[1].delay - right[1].delay)[0] || [];
      if (!timer) return false;
      timers.delete(id);
      timer.callback();
      // Let any continuations queued by the callback settle.
      await Promise.resolve();
      return true;
    }
  };
}

function fakeAnalyzer({ connected = true } = {}) {
  const calls = { fades: [], mixVolumes: [], resets: [], volumes: [], buffers: [] };
  let now = 100;
  return {
    calls,
    advance(seconds) {
      now += seconds;
    },
    currentTime: () => now,
    resume: async () => {},
    setVolume(element, value) {
      calls.volumes.push({ element, value });
    },
    setMixVolume(element, value) {
      calls.mixVolumes.push({ element, value });
      return connected;
    },
    resetMixElement(element) {
      calls.resets.push(element);
    },
    fadeVolume(element, from, to, at, seconds) {
      if (!connected) return false;
      calls.fades.push({ element, from, to, at, seconds });
      return true;
    },
    playPcmBuffer({ channels, sampleRate, when, offset, volume }) {
      const seconds = channels[0].length / sampleRate - offset;
      const handle = {
        startTime: Math.max(now, when),
        endTime: Math.max(now, when) + seconds,
        volumeCalls: [],
        fades: [],
        stopped: false,
        setVolume(value) {
          this.volumeCalls.push(value);
        },
        fade(from, to, at, fadeSeconds) {
          this.fades.push({ from, to, at, seconds: fadeSeconds });
        },
        stop() {
          this.stopped = true;
        }
      };
      calls.buffers.push({ when, offset, volume, handle });
      return handle;
    },
    async decodeAudio() {
      const sampleRate = 44100;
      const seconds = 60;
      const data = new Float32Array(sampleRate * seconds).fill(0.1);
      return {
        sampleRate,
        length: data.length,
        numberOfChannels: 2,
        getChannelData: () => data
      };
    }
  };
}

function fakeElement(currentTime = 0) {
  return {
    currentTime,
    paused: false,
    pauseCalls: 0,
    pause() {
      this.pauseCalls += 1;
      this.paused = true;
    },
    async play() {
      this.paused = false;
    }
  };
}

function readyPlan({
  transitionStart = 200,
  overlapSeconds = 16 * 60 / 126,
  outgoingTempoRatio = 1,
  incomingTempoRatio = 1
} = {}) {
  const targetBpm = 16 * 60 / overlapSeconds;
  const incomingCueTime = 20;
  return {
    ok: true,
    transitionStart,
    transitionEnd: transitionStart + overlapSeconds * outgoingTempoRatio,
    overlapSeconds,
    beats: 16,
    bassSwapFraction: 0.75,
    outgoingBpm: targetBpm,
    incomingBpm: targetBpm,
    targetBpm,
    outgoingTempoRatio,
    incomingTempoRatio,
    stretchRatio: outgoingTempoRatio,
    incomingCueTime,
    incomingResumeTime: incomingCueTime + overlapSeconds * incomingTempoRatio,
    strategy: 'beatmatched_crossfade',
    outgoingSlice: {
      start: Math.max(0, transitionStart - 1.5),
      end: transitionStart + overlapSeconds + 1.5,
      anchor: Math.min(1.5, transitionStart)
    },
    incomingSlice: {
      start: 18.5,
      end: 20 + overlapSeconds + 1.5,
      anchor: 1.5
    }
  };
}

function exactRenderResult(outgoing, options, overrides = {}) {
  const plan = options.plan;
  return {
    rendered: true,
    rejected: '',
    // The native result uses a human-readable diagnostic label while the
    // selected-plan input uses the canonical wire spelling.
    strategy: plan.strategy.replaceAll('_', ' '),
    summary: '',
    beats: plan.beats,
    duration: plan.duration,
    stretchRatio: Math.fround(plan.outgoingTempoRatio),
    incomingStretchRatio: Math.fround(plan.incomingTempoRatio),
    bpm: Math.fround(plan.targetBpm),
    sampleRate: outgoing.sampleRate,
    outgoingStart: plan.outgoingStart,
    incomingStart: plan.incomingStart,
    outgoingResume: plan.outgoingStart + plan.duration * plan.outgoingTempoRatio,
    incomingResume: plan.incomingStart + plan.duration * plan.incomingTempoRatio,
    channels: [new Float32Array(1000), new Float32Array(1000)],
    ...overrides
  };
}

function renderFor(plan, sampleRate = 44100) {
  return {
    channels: [
      new Float32Array(Math.round(plan.overlapSeconds * sampleRate)),
      new Float32Array(Math.round(plan.overlapSeconds * sampleRate))
    ],
    sampleRate,
    stretchRatio: plan.outgoingTempoRatio,
    incomingStretchRatio: plan.incomingTempoRatio
  };
}

test('only uses raw rendered PCM when per-source processing is flat', () => {
  assert.equal(wsolaProcessingCompatible(), true);
  assert.equal(wsolaProcessingCompatible({ normalizationEnabled: true }), false);
  assert.equal(wsolaProcessingCompatible({
    audioEngineConfig: { enabled: true, eqEnabled: true }
  }), false);
  assert.equal(wsolaProcessingCompatible({
    audioEngineConfig: { enabled: true, preampDb: 2 }
  }), true);
  assert.equal(wsolaProcessingCompatible({
    audioEngineConfig: { enabled: true, balance: -0.2 }
  }), false);
  assert.equal(wsolaProcessingCompatible({
    audioEngineConfig: { enabled: false, outputGainDb: -6 }
  }), false);
  assert.equal(wsolaProcessingCompatible({
    audioEngineConfig: { enabled: true },
    outgoingGainDb: -3
  }), false);
  assert.equal(wsolaProcessingCompatible({
    audioEngineConfig: { enabled: false, eqEnabled: true, preampDb: 2 },
    outgoingGainDb: -3
  }), true);
});

test('pair planning caches refusals until an authoritative input changes', () => {
  let calls = 0;
  const planner = (options) => {
    calls += 1;
    return { ok: false, reason: 'confidence-boundary', options };
  };
  const engine = createWsolaCrossfade({
    analyzer: fakeAnalyzer(),
    bridge: {},
    planner
  });
  const analysis = { analysisVersion: 11, status: 'ready', bpm: 120, frames: [{ time: 0, energy: 0.5 }] };
  const nextAnalysis = { analysisVersion: 11, status: 'ready', bpm: 122, frames: [{ time: 0, energy: 0.4 }] };
  const options = {
    fromTrackId: 'from',
    toTrackId: 'to',
    analysis,
    nextAnalysis,
    duration: 240,
    nextDuration: 200,
    mode: 'smart',
    settings: { maxStretchDeviation: 0.04 }
  };

  const first = engine.plan(options);
  const second = engine.plan(options);
  assert.strictEqual(second, first);
  assert.equal(calls, 1, 'a refusal was recomputed during the playback poll');

  analysis.bpm = 121;
  const enriched = engine.plan(options);
  assert.notStrictEqual(enriched, first);
  assert.equal(calls, 2, 'an analysis fingerprint change did not invalidate the plan');

  engine.plan({ ...options, settings: { maxStretchDeviation: 0.03 } });
  engine.plan({ ...options, toTrackId: 'another-track' });
  assert.equal(calls, 4, 'settings and pair identity must participate in the cache key');
});

test('prepare sends the authoritative plan rebased into exact padded slices', async () => {
  const analyzer = fakeAnalyzer();
  const rendered = [];
  const masked = [];
  const engine = createWsolaCrossfade({
    analyzer,
    bridge: {
      vocalMask: async (channels, sampleRate) => {
        masked.push({ channels, sampleRate });
        return { curve: [0.2, 0.8] };
      },
      renderTransition: async (outgoing, incoming, options) => {
        rendered.push({ outgoing, incoming, options });
        return exactRenderResult(outgoing, options);
      }
    }
  });

  const plan = {
    ...readyPlan({ transitionStart: 40, overlapSeconds: 16 * 60 / 126.123456 }),
    outgoingGrid: { bpm: 120, beats: [39, 40, 44, 48, 49], downbeats: [40, 48] },
    incomingGrid: { bpm: 120, beats: [19, 20, 24, 28, 29], downbeats: [20, 28] }
  };
  await engine.prepare({ fromTrackId: 'a', toTrackId: 'b', fromUrl: 'u1', toUrl: 'u2', plan });

  assert.equal(engine.preparationStatus('a', 'b'), 'ready');
  assert.equal(engine.preparationStatus('a', 'other'), 'idle');
  assert.ok(engine.preparedTransition('a', 'b')?.render);
  assert.equal(rendered.length, 1);

  const call = rendered[0];
  assert.equal(call.outgoing.channels.length, 2);
  assert.equal(call.outgoing.sampleRate, 44100);
  assert.equal(call.outgoing.bpm, plan.outgoingBpm);

  const outgoingSliceStart = plan.transitionStart - 1.5;
  const expectedFrames = Math.round((plan.overlapSeconds + 3) * 44100);
  assert.ok(Math.abs(call.outgoing.channels[0].length - expectedFrames) <= 1);
  assert.deepEqual(call.outgoing.downbeats, [1.5, 9.5]);
  assert.deepEqual(call.incoming.downbeats, [1.5, 9.5]);
  assert.deepEqual(call.options.plan, {
    outgoingStart: plan.transitionStart - outgoingSliceStart,
    incomingStart: 1.5,
    duration: plan.overlapSeconds,
    beats: plan.beats,
    outgoingBpm: plan.outgoingBpm,
    incomingBpm: plan.incomingBpm,
    targetBpm: plan.targetBpm,
    outgoingTempoRatio: plan.outgoingTempoRatio,
    incomingTempoRatio: plan.incomingTempoRatio,
    strategy: plan.strategy
  });
  assert.equal('outgoing' in call.options, false);
  assert.equal('incoming' in call.options, false);
  assert.equal('beatLengths' in call.options, false);
  assert.deepEqual(call.options.duckCurve, [0.2, 0.8]);
  assert.equal(masked.length, 1);
  assert.equal(masked[0].sampleRate, 44100);
  assert.ok(Math.abs(masked[0].channels[0].length - plan.overlapSeconds * 44100) <= 1);
  assert.strictEqual(engine.preparedTransition('a', 'b').plan, plan);
});

test('prepare refuses a returned plan identity mismatch greater than one sample', async () => {
  const reports = [];
  const engine = createWsolaCrossfade({
    analyzer: fakeAnalyzer(),
    bridge: {
      renderTransition: async (outgoing, incoming, options) => exactRenderResult(outgoing, options, {
        outgoingStart: options.plan.outgoingStart + 2 / outgoing.sampleRate
      })
    },
    report: (event, detail) => reports.push({ event, detail })
  });

  const result = await engine.prepare({
    fromTrackId: 'a',
    toTrackId: 'b',
    fromUrl: 'u1',
    toUrl: 'u2',
    plan: readyPlan({ transitionStart: 40, overlapSeconds: 8 })
  });

  assert.equal(result, null);
  assert.equal(engine.preparationStatus('a', 'b'), 'failed');
  assert.equal(engine.preparedTransition('a', 'b'), null);
  assert.equal(
    reports.find((entry) => entry.event === 'wsola-prepare-refused')?.detail?.reason,
    'render-plan-mismatch'
  );
});

test('a refused render marks the pairing failed instead of throwing', async () => {
  const engine = createWsolaCrossfade({
    analyzer: fakeAnalyzer(),
    bridge: {
      renderTransition: async () => ({ rendered: false, rejected: 'tempo', channels: [] })
    }
  });
  const result = await engine.prepare({
    fromTrackId: 'a', toTrackId: 'b', fromUrl: 'u1', toUrl: 'u2', plan: readyPlan()
  });
  assert.equal(result, null);
  assert.equal(engine.preparationStatus('a', 'b'), 'failed');
  assert.equal(engine.preparedTransition('a', 'b'), null);
});

test('start schedules the overlap on the context clock and promotes at the swap', async () => {
  const clock = fakeClock();
  const originalWindow = globalThis.window;
  globalThis.window = clock.window;
  try {
    const analyzer = fakeAnalyzer();
    const engine = createWsolaCrossfade({ analyzer, bridge: {} });
    const plan = readyPlan({ transitionStart: 200, overlapSeconds: 8 });
    const fromAudio = fakeElement(199.4);
    const toAudio = fakeElement(0);
    const events = [];

    const startPromise = engine.start({
      fromAudio,
      toAudio,
      plan,
      render: renderFor(plan),
      volume: 0.8,
      onPromote: () => events.push('promote'),
      onComplete: () => events.push('complete'),
      onError: (error) => events.push(`error:${error.message}`)
    });
    await Promise.resolve();
    await Promise.resolve();

    assert.equal(engine.isActive(), true);
    const buffer = analyzer.calls.buffers[0];
    assert.ok(Math.abs(buffer.when - 100.6) < 0.01, `when=${buffer.when}`);
    assert.equal(buffer.offset, 0);
    assert.equal(buffer.volume, 0.8);
    // Handing over must be complementary and simultaneous: the element fades
    // out over exactly the window the buffer fades in. Any gap where both
    // carry the same audio at full gain is the comb filtering that made the
    // transition sound like static.
    const outgoingFade = analyzer.calls.fades.find((fade) => fade.element === fromAudio);
    const bufferIn = buffer.handle.fades[0];
    assert.deepEqual(
      [outgoingFade.from, outgoingFade.to],
      [1, 0],
      'outgoing mix envelope must fade down, not mute asymptotically'
    );
    assert.deepEqual([bufferIn.from, bufferIn.to], [0, 1]);
    assert.ok(Math.abs(outgoingFade.at - bufferIn.at) < 1e-9, 'fades must start together');
    assert.equal(outgoingFade.seconds, bufferIn.seconds);
    assert.ok(outgoingFade.seconds <= 0.02, `handoff window too wide: ${outgoingFade.seconds}s`);

    assert.ok(Math.abs(toAudio.currentTime - plan.incomingCueTime) < 0.01);
    assert.equal(toAudio.paused, false);

    const incomingFade = analyzer.calls.fades.find((fade) => fade.element === toAudio);
    const bufferOut = buffer.handle.fades[1];
    assert.deepEqual([incomingFade.from, incomingFade.to], [0, 1]);
    assert.deepEqual([bufferOut.from, bufferOut.to], [1, 0]);
    assert.ok(Math.abs(incomingFade.at - bufferOut.at) < 1e-9, 'fades must start together');
    assert.ok(
      Math.abs(incomingFade.at + incomingFade.seconds - buffer.handle.endTime) < 1e-9,
      'the exchange must complete exactly as the buffer ends'
    );

    // No drift-correcting seek may land near the handoff; the element needs
    // settled playback before it becomes audible.
    const lastCorrection = plan.overlapSeconds * 0.375;
    assert.ok(lastCorrection < plan.overlapSeconds - 1);

    analyzer.advance(0.6 + 8 * 0.75);
    while (events.length === 0 && await clock.runNext()) { /* advance */ }
    assert.deepEqual(events, ['promote']);
    assert.equal(fromAudio.pauseCalls, 1, 'outgoing released at promote');

    analyzer.advance(8 * 0.25 + 0.2);
    while (await clock.runNext()) { /* drain */ }
    await startPromise;
    assert.deepEqual(events, ['promote', 'complete']);
    assert.equal(engine.isActive(), false);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('cancel before the swap restores the outgoing element at the stretched position', async () => {
  const clock = fakeClock();
  const originalWindow = globalThis.window;
  globalThis.window = clock.window;
  try {
    const analyzer = fakeAnalyzer();
    const engine = createWsolaCrossfade({ analyzer, bridge: {} });
    const plan = readyPlan({
      transitionStart: 200,
      overlapSeconds: 8,
      outgoingTempoRatio: 1.05
    });
    const render = renderFor(plan);
    const fromAudio = fakeElement(199.9);
    const toAudio = fakeElement(0);

    const startPromise = engine.start({
      fromAudio,
      toAudio,
      plan,
      render,
      volume: 1,
      onPromote: () => {},
      onComplete: () => {},
      onError: () => {}
    });
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(engine.isActive(), true);

    // Three seconds into the overlap the element clock and the stretched
    // buffer have visibly diverged; cancel must realign before unmuting.
    analyzer.advance(0.1 + 3);
    fromAudio.currentTime = 203;
    engine.cancel();

    assert.equal(engine.isActive(), false);
    assert.ok(analyzer.calls.buffers[0].handle.stopped);
    assert.ok(Math.abs(fromAudio.currentTime - (200 + 3 * 1.05)) < 0.02,
      `expected stretched realign, got ${fromAudio.currentTime}`);
    assert.equal(toAudio.paused, true);
    const restored = analyzer.calls.volumes.filter((entry) => entry.element === fromAudio).pop();
    assert.equal(restored.value, 1);
    assert.equal(await startPromise, false);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('a cancelled transition reports its caller and the tail it discarded', async () => {
  const clock = fakeClock();
  const originalWindow = globalThis.window;
  globalThis.window = clock.window;
  try {
    const analyzer = fakeAnalyzer();
    const reports = [];
    const engine = createWsolaCrossfade({
      analyzer,
      bridge: {},
      report: (event, detail) => reports.push({ event, detail })
    });
    const plan = readyPlan({ transitionStart: 200, overlapSeconds: 8 });
    const fromAudio = fakeElement(199.9);
    const toAudio = fakeElement(0);
    const startPromise = engine.start({
      fromAudio,
      toAudio,
      plan,
      render: { channels: [new Float32Array(64), new Float32Array(64)], sampleRate: 48000, stretchRatio: 1 },
      volume: 1,
      onPromote: () => {},
      onComplete: () => {},
      onError: () => {}
    });
    await Promise.resolve();
    await Promise.resolve();

    analyzer.advance(0.1 + 5);
    engine.cancel('audio-pause-event');

    const cancelled = reports.find((entry) => entry.event === 'wsola-cancelled');
    assert.ok(cancelled, 'expected a wsola-cancelled report');
    assert.equal(cancelled.detail.reason, 'audio-pause-event');
    assert.ok(Math.abs(cancelled.detail.elapsedSeconds - 5) < 0.05);
    // Three seconds of the rendered overlap never played.
    assert.ok(Math.abs(cancelled.detail.remainingSeconds - 3) < 0.05,
      `expected ~3s discarded, got ${cancelled.detail.remainingSeconds}`);
    assert.equal(await startPromise, false);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('an uninstrumented cancel still names itself', async () => {
  const clock = fakeClock();
  const originalWindow = globalThis.window;
  globalThis.window = clock.window;
  try {
    const analyzer = fakeAnalyzer();
    const reports = [];
    const engine = createWsolaCrossfade({
      analyzer,
      bridge: {},
      report: (event, detail) => reports.push({ event, detail })
    });
    const startPromise = engine.start({
      fromAudio: fakeElement(199.9),
      toAudio: fakeElement(0),
      plan: readyPlan({ transitionStart: 200, overlapSeconds: 8 }),
      render: { channels: [new Float32Array(64), new Float32Array(64)], sampleRate: 48000, stretchRatio: 1 },
      volume: 1,
      onPromote: () => {},
      onComplete: () => {},
      onError: () => {}
    });
    await Promise.resolve();
    await Promise.resolve();
    engine.cancel();

    assert.equal(
      reports.find((entry) => entry.event === 'wsola-cancelled').detail.reason,
      'unspecified'
    );
    assert.equal(await startPromise, false);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('volume changes update master gains without replacing scheduled handoff fades', async () => {
  const clock = fakeClock();
  const originalWindow = globalThis.window;
  globalThis.window = clock.window;
  try {
    const analyzer = fakeAnalyzer();
    const engine = createWsolaCrossfade({ analyzer, bridge: {} });
    const plan = readyPlan({ transitionStart: 200, overlapSeconds: 8 });
    const fromAudio = fakeElement(199.4);
    const toAudio = fakeElement(0);

    const startPromise = engine.start({
      fromAudio,
      toAudio,
      plan,
      render: renderFor(plan),
      volume: 0.8
    });
    await Promise.resolve();
    await Promise.resolve();

    const scheduledElementFades = [...analyzer.calls.fades];
    const scheduledBufferFades = [...analyzer.calls.buffers[0].handle.fades];
    engine.setTargetVolume(0.35);

    assert.deepEqual(analyzer.calls.fades, scheduledElementFades);
    assert.deepEqual(analyzer.calls.buffers[0].handle.fades, scheduledBufferFades);
    assert.deepEqual(analyzer.calls.volumes.slice(-2), [
      { element: fromAudio, value: 0.35 },
      { element: toAudio, value: 0.35 }
    ]);
    assert.deepEqual(analyzer.calls.buffers[0].handle.volumeCalls, [0.35]);

    engine.cancel();
    assert.equal(await startPromise, false);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('start refuses when called too late for a clean downbeat', async () => {
  const analyzer = fakeAnalyzer();
  const engine = createWsolaCrossfade({ analyzer, bridge: {} });
  const plan = readyPlan({ transitionStart: 200 });
  const didStart = await engine.start({
    fromAudio: fakeElement(200.6),
    toAudio: fakeElement(0),
    plan,
    render: renderFor(plan),
    volume: 1
  });
  assert.equal(didStart, false);
  assert.equal(engine.isActive(), false);
  assert.equal(analyzer.calls.buffers.length, 0);
});

test('rechecks media time after context resume and maps late input time onto stretched output', async () => {
  const clock = fakeClock();
  const originalWindow = globalThis.window;
  globalThis.window = clock.window;
  try {
    const analyzer = fakeAnalyzer();
    const engine = createWsolaCrossfade({ analyzer, bridge: {} });
    const plan = readyPlan({
      transitionStart: 200,
      overlapSeconds: 8,
      outgoingTempoRatio: 0.95,
      incomingTempoRatio: 1.02
    });
    const render = renderFor(plan);
    const fromAudio = fakeElement(199.9);
    const toAudio = fakeElement(0);
    analyzer.resume = async () => {
      analyzer.advance(0.15);
      fromAudio.currentTime += 0.15;
    };

    const startPromise = engine.start({
      fromAudio,
      toAudio,
      plan,
      render,
      volume: 1
    });
    await Promise.resolve();
    await Promise.resolve();

    const buffer = analyzer.calls.buffers[0];
    const expectedOffset = 0.05 / 0.95;
    assert.ok(Math.abs(buffer.offset - expectedOffset) < 1e-9);
    assert.ok(Math.abs(
      toAudio.currentTime - (plan.incomingCueTime + expectedOffset * 1.02)
    ) < 1e-9);

    // The first drift correction is 15% into the output interval. Its media
    // target must advance on the incoming source timeline, not one-for-one
    // with output time.
    toAudio.currentTime = 0;
    analyzer.advance(plan.overlapSeconds * 0.15 - expectedOffset);
    assert.equal(await clock.runNext(), true);
    assert.ok(Math.abs(
      toAudio.currentTime - (plan.incomingCueTime + plan.overlapSeconds * 0.15 * 1.02)
    ) < 1e-9);

    engine.cancel();
    assert.equal(await startPromise, false);
  } finally {
    globalThis.window = originalWindow;
  }
});

test('start refuses when the elements are outside the audio graph', async () => {
  const clock = fakeClock();
  const originalWindow = globalThis.window;
  globalThis.window = clock.window;
  try {
    // fadeVolume returning false means the scheduled handoff would never
    // happen, leaving the outgoing element audible under the buffer.
    const analyzer = fakeAnalyzer({ connected: false });
    const engine = createWsolaCrossfade({ analyzer, bridge: {} });
    const plan = readyPlan({ transitionStart: 200, overlapSeconds: 8 });
    const fromAudio = fakeElement(199.4);
    const toAudio = fakeElement(0);
    const errors = [];

    const didStart = await engine.start({
      fromAudio,
      toAudio,
      plan,
      render: renderFor(plan),
      volume: 1,
      onError: (error) => errors.push(error.message)
    });

    assert.equal(didStart, false);
    assert.equal(engine.isActive(), false);
    assert.equal(errors.length, 1);
    assert.match(errors[0], /audio graph/);
    assert.ok(analyzer.calls.buffers[0].handle.stopped, 'the scheduled buffer must be torn down');
  } finally {
    globalThis.window = originalWindow;
  }
});
