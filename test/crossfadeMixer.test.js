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
    highPass: { context, frequency: audioParam() },
    lowPass: { context, frequency: audioParam() }
  };
}

test('DJ filters stage an incoming bed before the dominance handoff', () => {
  const fromAudio = {};
  const toAudio = {};
  const fromNode = mixNode();
  const toNode = mixNode();
  const nodes = new Map([[fromAudio, fromNode], [toAudio, toNode]]);
  const mixer = createCrossfadeMixer({
    connectElement: (audio) => nodes.get(audio),
    currentTime: () => 100
  });

  const timing = mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    targetVolume: 1,
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
  assert.ok(toNode.gain.gain.events.some((event) =>
    event.type === 'ramp' && event.value === 0.28 && event.time === 104
  ));
  const incomingCurve = toNode.gain.gain.events.find((event) => event.type === 'curve');
  const dominanceIndex = Math.ceil((incomingCurve.values.length - 1) * 0.58);
  assert.ok(Math.abs(incomingCurve.first - 0.28) < 0.0001);
  assert.ok(incomingCurve.values[dominanceIndex] > 0.999);
  assert.ok(incomingCurve.values.slice(dominanceIndex).every((value) => value > 0.999));
  // 4s preroll is NOT a long preroll (<=6s), so original filter values apply
  assert.ok(toNode.highPass.frequency.events.some((event) =>
    event.type === 'set' && event.value === 1600 && event.time === 100
  ));
  assert.ok(toNode.highPass.frequency.events.some((event) =>
    event.type === 'curve' && event.time === 104 && event.last === 20
  ));
  assert.ok(fromNode.lowPass.frequency.events.some((event) =>
    event.type === 'curve' && event.time === 104 && event.last === 200
  ));
});

test('same-beat blends use the gentler incoming filter', () => {
  const fromAudio = {};
  const toAudio = {};
  const fromNode = mixNode();
  const toNode = mixNode();
  const nodes = new Map([[fromAudio, fromNode], [toAudio, toNode]]);
  const mixer = createCrossfadeMixer({
    connectElement: (audio) => nodes.get(audio),
    currentTime: () => 20
  });

  const timing = mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    targetVolume: 1,
    duration: 28,
    handoffStartSeconds: 17,
    handoffDuration: 10,
    transitionStyle: 'dj_blend',
    leadTime: 0
  });

  assert.equal(timing.handoffStart, 37);
  assert.equal(timing.promotionTime, 42.8);
  // 17s preroll is a long preroll (>6s), so uses 500Hz HP cutoff
  assert.ok(toNode.highPass.frequency.events.some((event) =>
    event.type === 'set' && event.value === 500 && event.time === 20
  ));
  // Long preroll uses 0.20 bed gain
  assert.ok(toNode.gain.gain.events.some((event) =>
    event.type === 'ramp' && Math.abs(event.value - 0.20) < 0.001
  ));
});

test('long-preroll filter curves share an exact boundary', () => {
  const fromAudio = {};
  const toAudio = {};
  const fromNode = mixNode();
  const toNode = mixNode();
  const nodes = new Map([[fromAudio, fromNode], [toAudio, toNode]]);
  const mixer = createCrossfadeMixer({
    connectElement: (audio) => nodes.get(audio),
    currentTime: () => 0.016000000000000007
  });

  mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    targetVolume: 1,
    duration: 24,
    handoffStartSeconds: 17.1234,
    handoffDuration: 6,
    transitionStyle: 'dj_blend',
    leadTime: 0
  });

  const curves = toNode.highPass.frequency.events.filter((event) => event.type === 'curve');
  assert.equal(curves.length, 2);
  assert.equal(curves[0].time + curves[0].duration, curves[1].time);
});

test('DJ styles schedule DJ gains and filters even when handoffStart equals startTime', () => {
  const fromAudio = {};
  const toAudio = {};
  const fromNode = mixNode();
  const toNode = mixNode();
  const nodes = new Map([[fromAudio, fromNode], [toAudio, toNode]]);
  const mixer = createCrossfadeMixer({
    connectElement: (audio) => nodes.get(audio),
    currentTime: () => 50
  });

  const timing = mixer.scheduleCrossfade({
    fromAudio,
    toAudio,
    targetVolume: 1,
    duration: 8,
    handoffStartSeconds: 0,
    handoffDuration: 8,
    transitionStyle: 'dj_blend',
    bassSwap: true,
    leadTime: 0
  });

  assert.equal(timing.startTime, 50);
  assert.equal(timing.handoffStart, 50);
  assert.ok(toNode.highPass.frequency.events.some((event) => event.type === 'set' && event.value === 350));
  assert.ok(toNode.gain.gain.events.some((event) => event.type === 'curve' && event.time === 50));
});
