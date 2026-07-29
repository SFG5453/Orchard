import assert from 'node:assert/strict';
import test from 'node:test';

import {
  MIN_USABLE_ESSENTIA_CONFIDENCE,
  mapConfidence,
  refineBeatConfidence,
  resetEssentia
} from '../electron/audio/essentiaBeatConfidence.js';

// A stand-in for the WASM module. Tests must not load real Essentia: it is
// several megabytes, takes about a second per track, and its numbers belong in
// measurement runs rather than in assertions.
function fakeEngine({ confidence = 3, bpm = 126, throws = false } = {}) {
  const deleted = [];
  const pkg = {
    EssentiaWASM: {},
    Essentia: function Essentia() {
      return {
        arrayToVector: (samples) => ({ samples, delete: () => deleted.push(true) }),
        RhythmExtractor2013: () => {
          if (throws) throw new Error('essentia exploded');
          return { confidence, bpm };
        }
      };
    }
  };
  return { load: async () => pkg, deleted };
}

test('confidence bands anchor Orchard thresholds to Essentia accuracy bands', () => {
  // Essentia documents (1.5, 3.5] as "good, around 80% accuracy in AMLt".
  // Orchard beat-matches at 0.55 and allows DJ-assisted transitions at 0.2.
  assert.ok(mapConfidence(0) < 0.2, 'very low must fall below the DJ-assisted floor');
  assert.ok(mapConfidence(1.0) <= 0.2, 'the low band starts at the DJ-assisted floor');
  assert.ok(mapConfidence(3.5) >= 0.55, 'the top of the good band must beat-match');
  assert.ok(mapConfidence(5.32) >= 0.99, 'the ceiling maps to full confidence');
  assert.ok(mapConfidence(9) <= 1, 'out-of-range input stays clamped');
});

test('the confidence map is monotonic', () => {
  let previous = -1;
  for (let value = 0; value <= 5.32; value += 0.13) {
    const mapped = mapConfidence(value);
    assert.ok(mapped >= previous, `not monotonic at ${value}: ${mapped} < ${previous}`);
    previous = mapped;
  }
});

test('unusable input yields no opinion rather than a low confidence', async () => {
  resetEssentia();
  assert.equal(await refineBeatConfidence(new Float32Array(0), 11025), null);
  assert.equal(await refineBeatConfidence(new Float32Array(16), 0), null);
  assert.equal(await refineBeatConfidence(null, 11025), null);
});

test('a very low Essentia reading does not override the native confidence', async () => {
  // Essentia documents this band as "the input signal is hard for the employed
  // candidate beat trackers" -- a fact about its trackers, not about the track.
  resetEssentia();
  const { load } = fakeEngine({ confidence: MIN_USABLE_ESSENTIA_CONFIDENCE - 0.01 });
  assert.equal(await refineBeatConfidence(new Float32Array(4096), 11025, { load }), null);
});

test('a usable reading is mapped and reported with its source values', async () => {
  resetEssentia();
  const { load } = fakeEngine({ confidence: 4.12, bpm: 90.01 });
  const result = await refineBeatConfidence(new Float32Array(4096), 11025, { load });

  assert.ok(result, 'expected a refinement');
  assert.equal(result.essentiaConfidence, 4.12);
  assert.equal(result.essentiaBpm, 90.01);
  assert.equal(result.beatConfidence, mapConfidence(4.12));
  assert.ok(result.beatConfidence >= 0.55, 'an excellent reading must beat-match');
});

test('a failed load leaves the native confidence in place and can be retried', async () => {
  resetEssentia();
  let attempts = 0;
  const failing = async () => {
    attempts += 1;
    throw new Error('module missing');
  };
  assert.equal(await refineBeatConfidence(new Float32Array(4096), 11025, { load: failing }), null);
  // A poisoned cached promise would make every later call fail without retrying.
  assert.equal(await refineBeatConfidence(new Float32Array(4096), 11025, { load: failing }), null);
  assert.equal(attempts, 2, 'a failed load must not be cached');
});

test('an exploding extractor is contained and releases its WASM vector', async () => {
  resetEssentia();
  const engine = fakeEngine({ throws: true });
  assert.equal(await refineBeatConfidence(new Float32Array(4096), 11025, { load: engine.load }), null);
  assert.equal(engine.deleted.length, 1, 'the vector must be freed even when extraction throws');
});

test('the WASM vector is released on the success path too', async () => {
  resetEssentia();
  const engine = fakeEngine({ confidence: 3 });
  await refineBeatConfidence(new Float32Array(4096), 11025, { load: engine.load });
  assert.equal(engine.deleted.length, 1, 'leaking one vector per track would grow the heap');
});

test('a budget already spent on loading skips the extractor', async () => {
  resetEssentia();
  let clock = 0;
  const engine = fakeEngine({ confidence: 3 });
  const result = await refineBeatConfidence(new Float32Array(4096), 11025, {
    load: engine.load,
    timeoutMs: 50,
    now: () => (clock += 100)
  });
  assert.equal(result, null);
  assert.equal(engine.deleted.length, 0, 'the extractor must not have run');
});
