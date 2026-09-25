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
  CHOREOGRAPHY_SCHEMA_VERSION,
  CHOREOGRAPHY_STRATEGY,
  CURVE_INTERPOLATION,
  createAutomationPoint,
  createTransitionChoreography,
  evaluateAutomationCurve,
  validateChoreography
} from '../src/audio/crossfade/transitionChoreography.js';

function validChoreography(overrides = {}) {
  return {
    schemaVersion: CHOREOGRAPHY_SCHEMA_VERSION,
    strategy: CHOREOGRAPHY_STRATEGY.STAGED_BLEND,
    outgoing: { start: 100.0, end: 108.0, tempoRatio: 1.0 },
    incoming: { cue: 0.0, arrival: 4.0, resume: 8.0, tempoRatio: 1.0 },
    duration: 8.0,
    dominancePoint: 0.55,
    curves: {
      outgoingGain: [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 0.0)],
      incomingGain: [createAutomationPoint(0.0, 0.0), createAutomationPoint(1.0, 1.0)],
      outgoingLowPass: [createAutomationPoint(0.0, 20000), createAutomationPoint(1.0, 700)],
      outgoingBass: [
        createAutomationPoint(0.0, 1.0),
        createAutomationPoint(0.45, 1.0),
        createAutomationPoint(0.55, 0.0),
        createAutomationPoint(1.0, 0.0)
      ],
      incomingBass: [
        createAutomationPoint(0.0, 0.0),
        createAutomationPoint(0.45, 0.0),
        createAutomationPoint(0.55, 1.0),
        createAutomationPoint(1.0, 1.0)
      ]
    },
    bassSwapPoint: 0.5,
    confidence: 0.95,
    diagnostics: null,
    fallback: null,
    ...overrides
  };
}

test('valid choreography passes validation', () => {
  const choreo = validChoreography();
  const result = validateChoreography(choreo);
  assert.equal(result.valid, true, `Validation failed: ${result.errors.join('; ')}`);
});

test('validation rejects non-finite timing', () => {
  const invalid = validChoreography({
    outgoing: { start: NaN, end: 108.0, tempoRatio: 1.0 }
  });
  const result = validateChoreography(invalid);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some((err) => err.includes('outgoing start')));
});

test('validation rejects unsorted or out-of-range automation points', () => {
  const unsorted = validChoreography({
    curves: {
      ...validChoreography().curves,
      outgoingGain: [
        createAutomationPoint(0.5, 1.0),
        createAutomationPoint(0.2, 0.5)
      ]
    }
  });
  const resultUnsorted = validateChoreography(unsorted);
  assert.equal(resultUnsorted.valid, false);
  assert.ok(resultUnsorted.errors.some((err) => err.includes('not sorted')));

  const outOfRange = validChoreography({
    curves: {
      ...validChoreography().curves,
      incomingGain: [createAutomationPoint(-0.1, 0.0), createAutomationPoint(1.2, 1.0)]
    }
  });
  const resultOutOfRange = validateChoreography(outOfRange);
  assert.equal(resultOutOfRange.valid, false);
  assert.ok(resultOutOfRange.errors.some((err) => err.includes('out of range')));
});

test('validation rejects arrival before cue', () => {
  const invalid = validChoreography({
    incoming: { cue: 4.0, arrival: 2.0, resume: 12.0, tempoRatio: 1.0 }
  });
  const result = validateChoreography(invalid);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some((err) => err.includes('arrival') && err.includes('before cue')));
});

test('validation rejects resume before cue', () => {
  const invalid = validChoreography({
    incoming: { cue: 8.0, arrival: 10.0, resume: 4.0, tempoRatio: 1.0 }
  });
  const result = validateChoreography(invalid);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some((err) => err.includes('resume') && err.includes('before cue')));
});

test('validation rejects duration inconsistent with source consumption', () => {
  const inconsistent = validChoreography({
    outgoing: { start: 100.0, end: 110.0, tempoRatio: 1.0 }, // consumes 10s
    duration: 8.0 // duration says 8s -> 10 != 8
  });
  const result = validateChoreography(inconsistent);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some((err) => err.includes('inconsistent with outgoing consumption')));
});

test('validation rejects bass ownership that leaves both decks active outside short swap ramp', () => {
  const dualBass = validChoreography({
    curves: {
      ...validChoreography().curves,
      outgoingBass: [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)],
      incomingBass: [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)]
    }
  });
  const result = validateChoreography(dualBass);
  assert.equal(result.valid, false);
  assert.ok(result.errors.some((err) => err.includes('Bass ownership leaves both decks active simultaneously')));
});

test('curve evaluation produces expected interpolated values', () => {
  const linear = [createAutomationPoint(0.0, 0.0, CURVE_INTERPOLATION.LINEAR), createAutomationPoint(1.0, 10.0)];
  assert.equal(evaluateAutomationCurve(linear, 0.0), 0.0);
  assert.equal(evaluateAutomationCurve(linear, 0.5), 5.0);
  assert.equal(evaluateAutomationCurve(linear, 1.0), 10.0);

  const smooth = [createAutomationPoint(0.0, 0.0, CURVE_INTERPOLATION.SMOOTH_STEP), createAutomationPoint(1.0, 1.0)];
  assert.equal(evaluateAutomationCurve(smooth, 0.5), 0.5);
  // at 0.25: 0.25^2 * (3 - 0.5) = 0.0625 * 2.5 = 0.15625
  assert.ok(Math.abs(evaluateAutomationCurve(smooth, 0.25) - 0.15625) < 1e-4);
});
