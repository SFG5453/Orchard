/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createAudioEngine,
  dbToGain,
  normalizeAudioEngineConfig
} from '../src/audio/engine/audioEngine.js';

function audioParam(initial = 0) {
  return {
    value: initial,
    targets: [],
    setTargetAtTime(value, time, constant) {
      this.value = value;
      this.targets.push({ value, time, constant });
    }
  };
}

function audioNode(properties = {}) {
  return {
    connections: [],
    connect(target) { this.connections.push(target); },
    ...properties
  };
}

function fakeAudioContext() {
  return {
    currentTime: 12,
    createGain: () => audioNode({ gain: audioParam(1) }),
    createStereoPanner: () => audioNode({ pan: audioParam(0) }),
    createBiquadFilter: () => audioNode({
      frequency: audioParam(0),
      gain: audioParam(0),
      Q: audioParam(1),
      type: ''
    })
  };
}

test('normalizes global output gain with a safe default and bounded range', () => {
  assert.equal(normalizeAudioEngineConfig().outputGainDb, 0);
  assert.equal(normalizeAudioEngineConfig({ outputGainDb: -80 }).outputGainDb, -24);
  assert.equal(normalizeAudioEngineConfig({ outputGainDb: 20 }).outputGainDb, 6);
});

test('global output gain remains active while the rest of the engine is bypassed', () => {
  const engine = createAudioEngine({ enabled: false, outputGainDb: -6 });
  const processor = engine.createProcessor({
    context: fakeAudioContext(),
    source: audioNode(),
    element: {}
  });

  assert.ok(Math.abs(processor.output.gain.value - dbToGain(-6)) < 1e-12);
  engine.update({ enabled: false, outputGainDb: -12 });
  assert.ok(Math.abs(processor.output.gain.value - dbToGain(-12)) < 1e-12);
});

test('native audio receives EQ, automatic gains, and per-deck gain changes', () => {
  const original = globalThis.orchardNativeAudio;
  const calls = [];
  globalThis.orchardNativeAudio = {
    setEngineConfig: (config) => calls.push(['config', config]),
    setAutoEqGains: (gains) => calls.push(['auto', gains]),
    setTrackGain: (deck, gain) => calls.push(['track', deck, gain])
  };

  try {
    const engine = createAudioEngine();
    engine.update({ enabled: true, eqEnabled: true, gains: [2, 1] });
    engine.setAutoEqGains([1, -1]);
    engine.setTrackGain({ __orchardNative: true, deck: 'next' }, -3);
  } finally {
    if (original === undefined) delete globalThis.orchardNativeAudio;
    else globalThis.orchardNativeAudio = original;
  }

  assert.equal(calls[0][0], 'config');
  assert.equal(calls[0][1].gains.length, 10);
  assert.deepEqual(calls[1], ['auto', [1, -1, 0, 0, 0, 0, 0, 0, 0, 0]]);
  assert.deepEqual(calls[2], ['track', 'next', -3]);
});
