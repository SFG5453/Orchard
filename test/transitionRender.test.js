import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const native = require('../native/build/Release/orchard_audio_analysis.node');

const SAMPLE_RATE = 44100;

// A steady low tone plus a steady mid tone. Continuous rather than percussive
// so measurements do not depend on where a window lands relative to a beat,
// and each track's low end sits at its own frequency so the bass handover can
// be attributed to one side or the other.
function track({ duration = 12, bass = 55, tone = 220, amplitude = 0.4 } = {}) {
  const samples = new Float32Array(Math.floor(duration * SAMPLE_RATE));
  for (let index = 0; index < samples.length; index += 1) {
    const time = index / SAMPLE_RATE;
    samples[index] = amplitude * (
      Math.sin(2 * Math.PI * bass * time) * 0.6 +
      Math.sin(2 * Math.PI * tone * time) * 0.4
    );
  }
  return samples;
}

function source(samples, bpm, anchor = 0) {
  return { channels: [samples, samples], anchor, bpm };
}

function rms(samples, from = 0, to = samples.length) {
  let total = 0;
  for (let index = from; index < to; index += 1) total += samples[index] ** 2;
  return Math.sqrt(total / Math.max(1, to - from));
}

function amplitudeAt(samples, from, length, frequency) {
  let real = 0;
  let imaginary = 0;
  for (let index = 0; index < length; index += 1) {
    const angle = 2 * Math.PI * frequency * (index / SAMPLE_RATE);
    real += samples[from + index] * Math.cos(angle);
    imaginary -= samples[from + index] * Math.sin(angle);
  }
  return (2 * Math.hypot(real, imaginary)) / length;
}

test('renders a matched-tempo overlap at the requested length', async () => {
  const result = await native.renderTransition(
    source(track(), 126),
    source(track({ bass: 85, tone: 330 }), 126),
    { sampleRate: SAMPLE_RATE, beats: 16 }
  );

  assert.equal(result.rendered, true, result.rejected);
  assert.equal(result.rejected, '');
  assert.equal(result.bpm, 126);
  assert.ok(Math.abs(result.stretchRatio - 1) < 1e-6);
  assert.equal(result.channels.length, 2);
  const expected = 16 * (60 / 126) * SAMPLE_RATE;
  assert.ok(
    Math.abs(result.channels[0].length - expected) < 2,
    `expected ~${expected} samples, got ${result.channels[0].length}`
  );
});

test('time-scales the outgoing track onto the incoming tempo', async () => {
  const result = await native.renderTransition(
    source(track(), 120),
    source(track({ bass: 85, tone: 330 }), 126),
    { sampleRate: SAMPLE_RATE, beats: 16 }
  );

  assert.equal(result.rendered, true, result.rejected);
  // The overlap runs on the incoming grid, so the outgoing track is the one
  // that moves. Reaching a faster grid compresses it: 120/126 = 0.9524, below
  // 1. A ratio above 1 here would mean the mix is being slowed onto a tempo
  // further from the target than it started.
  assert.ok(
    result.stretchRatio < 1,
    `outgoing was slowed onto a faster grid: ratio ${result.stretchRatio}`
  );
  assert.ok(
    Math.abs(result.stretchRatio - 120 / 126) < 1e-4,
    `unexpected stretch ratio ${result.stretchRatio}`
  );
  assert.equal(result.bpm, 126);

  // The mirrored pairing must move the other way. Pinning both directions is
  // what catches an inverted ratio, which otherwise still looks plausible.
  const slower = await native.renderTransition(
    source(track(), 126),
    source(track({ bass: 85, tone: 330 }), 120),
    { sampleRate: SAMPLE_RATE, beats: 16 }
  );
  assert.equal(slower.rendered, true, slower.rejected);
  assert.ok(
    Math.abs(slower.stretchRatio - 126 / 120) < 1e-4,
    `unexpected mirrored stretch ratio ${slower.stretchRatio}`
  );
});

test('holds a level rather than dipping through the middle', async () => {
  const result = await native.renderTransition(
    source(track(), 126),
    source(track({ bass: 85, tone: 330 }), 126),
    { sampleRate: SAMPLE_RATE, beats: 16 }
  );

  const output = result.channels[0];
  const third = Math.floor(output.length / 3);
  const opening = rms(output, 0, third);
  const middle = rms(output, third, third * 2);
  const closing = rms(output, third * 2, output.length);

  // A linear crossfade sags about 3 dB in the middle; equal power should stay
  // within roughly 1.5 dB across the whole overlap.
  for (const [name, value] of [['opening', opening], ['closing', closing]]) {
    const ratio = middle / value;
    assert.ok(
      ratio > 0.84 && ratio < 1.19,
      `level moved ${name}->middle by ${(20 * Math.log10(ratio)).toFixed(2)} dB`
    );
  }
});

test('crosses the two tracks on the handoff, not the middle of the overlap', async () => {
  const handoff = 0.75;
  // Tones well clear of the 200 Hz bass crossover, so these readings reflect
  // the main fade rather than the low-end swap.
  const outgoingTone = 900;
  const incomingTone = 1500;
  const result = await native.renderTransition(
    source(track({ bass: 55, tone: outgoingTone }), 126),
    source(track({ bass: 85, tone: incomingTone }), 126),
    { sampleRate: SAMPLE_RATE, beats: 16, handoff, bassSwap: 0.8 }
  );
  assert.equal(result.rendered, true, result.rejected);

  const output = result.channels[0];
  const window = Math.floor(SAMPLE_RATE * 0.4);
  const level = (fraction, tone) =>
    amplitudeAt(output, Math.floor(output.length * fraction - window / 2), window, tone);

  // At the handoff the two sit at equal power; the old symmetric fade would
  // have crossed at 0.5 and left the incoming track already dominant here.
  const ratio = level(handoff, outgoingTone) / level(handoff, incomingTone);
  assert.ok(
    ratio > 0.8 && ratio < 1.25,
    `tracks not level at the handoff: ${ratio.toFixed(3)}`
  );

  // Through the pre-roll the outgoing track still leads.
  assert.ok(
    level(0.4, outgoingTone) > level(0.4, incomingTone) * 1.5,
    'incoming track was not a bed underneath during the pre-roll'
  );
  // After the handoff the incoming track takes over.
  assert.ok(
    level(0.95, incomingTone) > level(0.95, outgoingTone) * 1.5,
    'incoming track did not take over after the handoff'
  );
});

test('a low bed keeps the outgoing track up until the handoff', async () => {
  const handoff = 0.5;
  const outgoingTone = 900;
  const incomingTone = 1500;
  const result = await native.renderTransition(
    source(track({ bass: 55, tone: outgoingTone }), 126),
    source(track({ bass: 85, tone: incomingTone }), 126),
    { sampleRate: SAMPLE_RATE, beats: 16, handoff, bed: 0.25, bassSwap: 0.6 }
  );
  assert.equal(result.rendered, true, result.rejected);

  const output = result.channels[0];
  const window = Math.floor(SAMPLE_RATE * 0.4);
  const level = (fraction, tone) =>
    amplitudeAt(output, Math.floor(output.length * fraction - window / 2), window, tone);

  // The outgoing track gives up under a dB across the whole pre-roll. Running
  // the fade's first half over it instead would cost 3 dB, which over a
  // pre-roll of any length is heard as the fade not having started.
  const held = level(handoff - 0.02, outgoingTone) / level(0.06, outgoingTone);
  assert.ok(
    held > 0.85,
    `outgoing track dropped ${(-20 * Math.log10(held)).toFixed(2)} dB before the handoff`
  );
  // The incoming track is present but well underneath, not level with it.
  assert.ok(
    level(handoff - 0.02, outgoingTone) > level(handoff - 0.02, incomingTone) * 1.8,
    'incoming intro came up level with the outgoing track instead of sitting under it'
  );
  // The audible fade is the tail: the outgoing track is gone by the end.
  assert.ok(
    level(0.94, outgoingTone) < level(0.06, outgoingTone) * 0.25,
    'outgoing track had not faded out by the end of the tail'
  );
});

test('a handoff at the midpoint reproduces the symmetric crossfade', async () => {
  const config = { sampleRate: SAMPLE_RATE, beats: 16, bassSwap: 0.6 };
  const [plain, explicit] = await Promise.all([
    native.renderTransition(source(track(), 126), source(track({ bass: 85, tone: 330 }), 126), config),
    native.renderTransition(source(track(), 126), source(track({ bass: 85, tone: 330 }), 126),
      { ...config, handoff: 0.5 })
  ]);

  assert.equal(plain.rendered, true, plain.rejected);
  assert.equal(explicit.rendered, true, explicit.rejected);
  assert.equal(plain.channels[0].length, explicit.channels[0].length);
  let worst = 0;
  for (let index = 0; index < plain.channels[0].length; index += 1) {
    worst = Math.max(worst, Math.abs(plain.channels[0][index] - explicit.channels[0][index]));
  }
  assert.ok(worst < 1e-6, `default handoff drifted from 0.5 by ${worst}`);
});

test('hands the low end from one track to the other', async () => {
  const result = await native.renderTransition(
    source(track({ bass: 55, tone: 220 }), 126),
    source(track({ bass: 85, tone: 330 }), 126),
    { sampleRate: SAMPLE_RATE, beats: 16, bassSwap: 0.6 }
  );
  assert.equal(result.rendered, true, result.rejected);

  const output = result.channels[0];
  const window = Math.floor(SAMPLE_RATE * 0.5);
  // Each track's bass measured against its own midrange, which cancels out the
  // crossfade gain and isolates what the filter did.
  const balance = (fraction, bass, tone) => {
    const at = Math.floor(output.length * fraction);
    return amplitudeAt(output, at, window, bass) / amplitudeAt(output, at, window, tone);
  };

  const outgoingEarly = balance(0.15, 55, 220);
  const outgoingLate = balance(0.9, 55, 220);
  const incomingEarly = balance(0.15, 85, 330);
  const incomingLate = balance(0.9, 85, 330);

  // A plain equal-power sum leaves both balances flat near 1.5 throughout,
  // piling two low ends on top of each other. A real swap moves them apart.
  assert.ok(
    outgoingLate < outgoingEarly / 3,
    `outgoing kept its low end: ${outgoingEarly.toFixed(3)} -> ${outgoingLate.toFixed(3)}`
  );
  assert.ok(
    incomingLate > incomingEarly * 3,
    `incoming never gained its low end: ${incomingEarly.toFixed(3)} -> ${incomingLate.toFixed(3)}`
  );
});

test('refuses pairings it cannot render transparently', async () => {
  const base = source(track(), 126);

  const farApart = await native.renderTransition(
    source(track(), 100),
    base,
    { sampleRate: SAMPLE_RATE, beats: 16 }
  );
  assert.equal(farApart.rendered, false);
  assert.match(farApart.rejected, /transparent stretch range/);

  const noTempo = await native.renderTransition(
    source(track(), 0),
    base,
    { sampleRate: SAMPLE_RATE, beats: 16 }
  );
  assert.equal(noTempo.rendered, false);
  assert.match(noTempo.rejected, /tempo/);

  const tooShort = await native.renderTransition(
    source(track({ duration: 2 }), 126),
    source(track({ duration: 2 }), 126),
    { sampleRate: SAMPLE_RATE, beats: 64 }
  );
  assert.equal(tooShort.rendered, false);
  assert.match(tooShort.rejected, /too short/);
});

test('aligns the two tracks on their anchors', async () => {
  const beat = 60 / 126;
  const incoming = track({ bass: 85, tone: 330 });
  const aligned = await native.renderTransition(
    source(track(), 126),
    source(incoming, 126, 0),
    { sampleRate: SAMPLE_RATE, beats: 8 }
  );
  const shifted = await native.renderTransition(
    source(track(), 126),
    source(incoming, 126, beat),
    { sampleRate: SAMPLE_RATE, beats: 8 }
  );

  assert.equal(aligned.rendered, true, aligned.rejected);
  assert.equal(shifted.rendered, true, shifted.rejected);
  let difference = 0;
  const length = Math.min(aligned.channels[0].length, shifted.channels[0].length);
  for (let index = 0; index < length; index += 1) {
    difference = Math.max(difference, Math.abs(aligned.channels[0][index] - shifted.channels[0][index]));
  }
  assert.ok(difference > 0.01, 'anchor offset did not change the rendered overlap');
});

test('rejects malformed sources without rendering', async () => {
  const base = source(track(), 126);
  assert.throws(() => native.renderTransition(null, base, { beats: 8 }), /object/);
  assert.throws(() => native.renderTransition({ bpm: 126 }, base, { beats: 8 }), /channels array/);
  assert.throws(
    () => native.renderTransition({ channels: [[1, 2]], bpm: 126 }, base, { beats: 8 }),
    /Float32Array/
  );
});
