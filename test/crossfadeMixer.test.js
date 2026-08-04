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

import { createCrossfadeMixer } from '../src/audio/crossfade/crossfadeMixer.js';

function audioParam() {
  return {
    events: [],
    cancelScheduledValues(time) {
      this.events.push({ type: 'cancel', time });
    },
    linearRampToValueAtTime(value, time) {
      this.events.push({ type: 'ramp', value, time });
    },
    setValueAtTime(value, time) {
      this.events.push({ type: 'set', value, time });
    },
    setTargetAtTime(value, time, constant) {
      this.events.push({ type: 'target', value, time, constant });
    },
    setValueCurveAtTime(values, time, duration) {
      this.events.push({
        type: 'curve',
        first: values[0],
        last: values.at(-1),
        time,
        duration,
        values: Array.from(values)
      });
    }
  };
}

function mixNode() {
  const context = { sampleRate: 48000 };
  return {
    gain: { gain: audioParam() },
    mixGain: { gain: audioParam() },
    bassGain: { gain: audioParam() },
    midDuck: { gain: audioParam() },
    highPass: { context, frequency: audioParam() },
    lowPass: { context, frequency: audioParam() }
  };
}

function mixerFor(nodes, now) {
  return createCrossfadeMixer({
    connectElement: (audio) => nodes.get(audio),
    currentTime: () => now
  });
}

function pair(now) {
  const fromAudio = {};
  const toAudio = {};
  const fromNode = mixNode();
  const toNode = mixNode();
  const nodes = new Map([[fromAudio, fromNode], [toAudio, toNode]]);
  return { fromAudio, toAudio, fromNode, toNode, mixer: mixerFor(nodes, now) };
}

function lastCurve(param) {
  return param.events.filter((event) => event.type === 'curve').at(-1);
}

test('DJ gains stay equal-power complementary across the whole fade', () => {
  const { fromAudio, toAudio, fromNode, toNode, mixer } = pair(50);

  const timing = mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 8,
    handoffStartSeconds: 0,
    handoffDuration: 8,
    transitionStyle: 'dj_blend',
    bassSwap: true,
    leadTime: 0
  });

  assert.equal(timing.startTime, 50);
  assert.equal(timing.handoffStart, 50);

  const incoming = lastCurve(toNode.mixGain.gain);
  const outgoing = lastCurve(fromNode.mixGain.gain);
  assert.ok(Math.abs(incoming.first) < 0.0001);
  assert.ok(Math.abs(incoming.last - 1) < 0.0001);
  assert.ok(Math.abs(outgoing.first - 1) < 0.0001);
  assert.ok(Math.abs(outgoing.last) < 0.0001);
  // No plateau: the incoming used to reach full gain at 58% and hold there
  // while the outgoing was still descending, so the tail of every DJ
  // transition carried two tracks at full level.
  for (let index = 1; index < incoming.values.length; index += 1) {
    assert.ok(incoming.values[index] > incoming.values[index - 1]);
    assert.ok(outgoing.values[index] < outgoing.values[index - 1]);
  }
  // Equal power: the two sum to unity in power at every point.
  incoming.values.forEach((value, index) => {
    const power = value * value + outgoing.values[index] * outgoing.values[index];
    assert.ok(Math.abs(power - 1) < 0.0001);
  });
});

test('the low end changes hands exclusively rather than overlapping', () => {
  const { fromAudio, toAudio, fromNode, toNode, mixer } = pair(0);

  mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 10,
    handoffStartSeconds: 0,
    handoffDuration: 10,
    transitionStyle: 'dj_blend',
    bassSwap: true,
    leadTime: 0
  });

  const out = lastCurve(fromNode.bassGain.gain);
  const into = lastCurve(toNode.bassGain.gain);
  assert.ok(Math.abs(out.first - 1) < 0.0001);
  assert.ok(Math.abs(out.last) < 0.0001);
  assert.ok(Math.abs(into.first) < 0.0001);
  assert.ok(Math.abs(into.last - 1) < 0.0001);
  // Equal power through the handover, so the bass never dips as it swaps.
  out.values.forEach((value, index) => {
    const power = value * value + into.values[index] * into.values[index];
    assert.ok(Math.abs(power - 1) < 0.0001);
  });
  // Six seconds in -- the absolute cap, which is what binds at this length.
  // Before it the outgoing still owns the low end.
  const crossing = into.values.findIndex((value, index) => value >= out.values[index]);
  assert.ok(Math.abs((crossing / (into.values.length - 1)) * 10 - 6) < 0.1);
  assert.ok(out.values[Math.round((out.values.length - 1) * 0.4)] > 0.99);
});

test('a long overlap does not scale the bass hold up with it', () => {
  const { fromAudio, toAudio, fromNode, toNode, mixer } = pair(0);

  mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 20,
    handoffStartSeconds: 0,
    handoffDuration: 20,
    transitionStyle: 'dj_blend',
    bassSwap: true,
    leadTime: 0
  });

  const out = lastCurve(fromNode.bassGain.gain);
  const into = lastCurve(toNode.bassGain.gain);
  const crossing = into.values.findIndex((value, index) => value >= out.values[index]);
  // Capped at six seconds rather than the fourteen 70% of this fade would give.
  assert.ok(Math.abs((crossing / (into.values.length - 1)) * 20 - 6) < 0.2);
  assert.ok(Math.abs(out.last) < 0.0001);
  assert.ok(Math.abs(into.last - 1) < 0.0001);
});

test('a short overlap still swaps on the fraction, not the cap', () => {
  const { fromAudio, toAudio, fromNode, toNode, mixer } = pair(0);

  mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 6,
    handoffStartSeconds: 0,
    handoffDuration: 6,
    transitionStyle: 'dj_blend',
    bassSwap: true,
    leadTime: 0
  });

  const out = lastCurve(fromNode.bassGain.gain);
  const into = lastCurve(toNode.bassGain.gain);
  const crossing = into.values.findIndex((value, index) => value >= out.values[index]);
  assert.ok(Math.abs((crossing / (into.values.length - 1)) * 6 - 4.2) < 0.1);
});

test('bass handover is skipped when the pairing did not ask for one', () => {
  const { fromAudio, toAudio, fromNode, toNode, mixer } = pair(0);

  mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 6,
    handoffStartSeconds: 0,
    handoffDuration: 6,
    transitionStyle: 'dj_filter',
    leadTime: 0
  });

  assert.deepEqual(fromNode.bassGain.gain.events, []);
  assert.deepEqual(toNode.bassGain.gain.events, []);
});

test('the outgoing mid band ducks at the rate the incoming arrives', () => {
  const { fromAudio, toAudio, fromNode, mixer } = pair(0);

  mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 8,
    handoffStartSeconds: 0,
    handoffDuration: 8,
    transitionStyle: 'dj_blend',
    bassSwap: true,
    leadTime: 0
  });

  const duck = lastCurve(fromNode.midDuck.gain);
  assert.ok(Math.abs(duck.first) < 0.0001);
  assert.ok(Math.abs(duck.last + 6) < 0.0001);
  // Follows the incoming track's power, so it is half spent at the midpoint.
  const middle = duck.values[Math.round((duck.values.length - 1) * 0.5)];
  assert.ok(Math.abs(middle + 3) < 0.15);
});

test('the sweep is colour, not separation, and never doubles the fade', () => {
  const blend = pair(0);
  blend.mixer.scheduleCrossfade({
    fromAudio: blend.fromAudio,
    toAudio: blend.toAudio,
    duration: 8,
    handoffStartSeconds: 0,
    handoffDuration: 8,
    transitionStyle: 'dj_blend',
    bassSwap: true,
    leadTime: 0
  });
  // Well clear of the 200 Hz crossover: sweeping to 200 attenuated the
  // outgoing track a second time on top of its own fade.
  assert.equal(lastCurve(blend.fromNode.lowPass.frequency).last, 2200);

  const filtered = pair(0);
  filtered.mixer.scheduleCrossfade({
    fromAudio: filtered.fromAudio,
    toAudio: filtered.toAudio,
    duration: 8,
    handoffStartSeconds: 0,
    handoffDuration: 8,
    transitionStyle: 'dj_filter',
    bassSwap: true,
    leadTime: 0
  });
  // A tempo-mismatched blend leans on the sweep harder to disguise the seam.
  assert.equal(lastCurve(filtered.fromNode.lowPass.frequency).last, 700);

  // Bass isolation is the gain handover's job now, so nothing high-passes the
  // incoming track.
  assert.deepEqual(blend.toNode.highPass.frequency.events, []);
});

test('a planned pre-roll stages the incoming track at bed level', () => {
  const { fromAudio, toAudio, toNode, mixer } = pair(100);

  const timing = mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 10,
    handoffStartSeconds: 4,
    handoffDuration: 6,
    transitionStyle: 'dj_filter',
    leadTime: 0
  });

  assert.deepEqual(timing, {
    startTime: 100,
    handoffStart: 104,
    promotionTime: 107.48,
    endTime: 110
  });
  assert.ok(toNode.mixGain.gain.events.some((event) =>
    event.type === 'ramp' && event.value === 0.28 && event.time === 104
  ));
  const incoming = lastCurve(toNode.mixGain.gain);
  assert.ok(Math.abs(incoming.first - 0.28) < 0.0001);
  assert.equal(incoming.time, 104);
  assert.equal(incoming.duration, 6);
});

test('non-DJ styles stay a plain equal-power fade', () => {
  const { fromAudio, toAudio, fromNode, toNode, mixer } = pair(0);

  mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    duration: 5,
    transitionStyle: 'equal_power',
    leadTime: 0
  });

  assert.deepEqual(fromNode.bassGain.gain.events, []);
  assert.deepEqual(fromNode.midDuck.gain.events, []);
  assert.deepEqual(fromNode.lowPass.frequency.events, []);
  assert.ok(lastCurve(toNode.mixGain.gain));
});

test('resetting a mix cancels its envelope without changing the master volume', () => {
  const element = {};
  const node = mixNode();
  const mixer = createCrossfadeMixer({
    connectElement: () => node,
    currentTime: () => 42
  });

  mixer.resetElement(element);

  assert.deepEqual(node.mixGain.gain.events.slice(0, 2), [
    { type: 'cancel', time: 42 },
    { type: 'target', value: 1, time: 42, constant: 0.02 }
  ]);
  assert.deepEqual(node.bassGain.gain.events, [
    { type: 'cancel', time: 42 },
    { type: 'target', value: 1, time: 42, constant: 0.02 }
  ]);
  assert.deepEqual(node.midDuck.gain.events, [
    { type: 'cancel', time: 42 },
    { type: 'target', value: 0, time: 42, constant: 0.02 }
  ]);
  assert.deepEqual(node.gain.gain.events, []);
});
