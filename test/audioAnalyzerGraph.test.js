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

import { createAudioAnalyzer } from '../src/audio/engine/audioAnalyzer.js';

function param(value = 0) {
  return {
    value,
    cancelScheduledValues() {},
    setValueAtTime(next) { this.value = next; },
    setTargetAtTime(next) { this.value = next; },
    linearRampToValueAtTime(next) { this.value = next; },
    setValueCurveAtTime(values) { this.value = values.at(-1); }
  };
}

function graphNode(kind) {
  return {
    kind,
    outputs: [],
    gain: param(1),
    frequency: param(0),
    Q: param(0),
    threshold: param(0),
    knee: param(0),
    ratio: param(0),
    attack: param(0),
    release: param(0),
    connect(target) { this.outputs.push(target); return target; }
  };
}

function mockContext() {
  const destination = graphNode('destination');
  return {
    sampleRate: 48000,
    currentTime: 0,
    destination,
    createGain: () => graphNode('gain'),
    createAnalyser: () => Object.assign(graphNode('analyser'), {
      frequencyBinCount: 512,
      getByteFrequencyData() {},
      getByteTimeDomainData() {},
      getFloatTimeDomainData() {}
    }),
    createBiquadFilter: () => graphNode('biquad'),
    createStereoPanner: () => Object.assign(graphNode('panner'), { pan: param(0) }),
    createDynamicsCompressor: () => graphNode('compressor'),
    createMediaElementSource: () => graphNode('source')
  };
}

// Every distinct route from `start` to the destination, as lists of nodes.
function routes(start, destination, seen = new Set()) {
  if (start === destination) return [[start]];
  if (seen.has(start)) return [];
  seen.add(start);
  const found = [];
  for (const output of start.outputs) {
    for (const tail of routes(output, destination, new Set(seen))) found.push([start, ...tail]);
  }
  return found;
}

function connectedElement() {
  const context = mockContext();
  const analyzer = createAudioAnalyzer();
  const element = { volume: 0.5 };
  const previousWindow = globalThis.window;
  globalThis.window = { AudioContext: function AudioContextStub() { return context; } };
  try {
    const node = analyzer.connectElement(element);
    return { analyzer, context, element, node };
  } finally {
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  }
}

test('ordinary playback reaches the output without passing through the crossover', () => {
  const { context, node } = connectedElement();

  const live = routes(node.mixGain, context.destination)
    .filter((route) => route.every((step) => step === node.mixGain ||
      step === context.destination ||
      step.gain.value !== 0 ||
      step.kind === 'biquad'));

  assert.equal(live.length, 1, 'exactly one audible path from the mix envelope');
  assert.ok(
    live[0].every((step) => step.kind !== 'biquad'),
    'the audible path carries no filters, so untransitioned playback is transparent'
  );
});

test('the crossover branches are wired but silent until a transition arms them', () => {
  const { node } = connectedElement();

  assert.equal(node.directBand.gain.value, 1);
  assert.equal(node.splitInput.gain.value, 0);
  assert.ok(node.splitInput.outputs.length >= 2, 'the split feeds both crossover branches');
});

test('the crossover halves stay phase-matched by sharing the post-sum output filters', () => {
  const { context, node } = connectedElement();

  const lowRoute = routes(node.bassGain, context.destination).at(0);
  const highRoute = routes(node.midDuck, context.destination).at(0);

  assert.ok(lowRoute?.includes(node.bandSum), 'the low branch sums before the output filters');
  assert.ok(highRoute?.includes(node.bandSum), 'the high branch sums before the output filters');
  assert.ok(
    lowRoute.slice(lowRoute.indexOf(node.bandSum)).includes(node.highPass) &&
      highRoute.slice(highRoute.indexOf(node.bandSum)).includes(node.highPass),
    'both branches see the same static filtering after the sum'
  );
  // The sweep is the one deliberate asymmetry: it colours the high branch only.
  assert.ok(!lowRoute.includes(node.lowPass) && highRoute.includes(node.lowPass));
});

test('the element hands its volume to the graph rather than attenuating itself', () => {
  const { element, node } = connectedElement();

  assert.equal(element.volume, 1);
  assert.equal(node.gain.gain.value, 0.5);
});
