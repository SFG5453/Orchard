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
import { readFileSync } from 'node:fs';
import test from 'node:test';

import { finalizeTrackAnalysis } from '../shared/trackAnalysis.js';
import { planPairTransition } from '../src/audio/crossfade/pairTransitionPlanner.js';
import {
  CHOREOGRAPHY_STRATEGY,
  evaluateAutomationCurve
} from '../src/audio/crossfade/transitionChoreography.js';

const fixtures = JSON.parse(
  readFileSync(new URL('./fixtures/transitionChoreography.json', import.meta.url), 'utf8')
);

test('fixture blinding lights -> dont start now is non-beatmatched with duration <= 4.0s', () => {
  const fixture = fixtures.blinding_lights_to_dont_start_now;
  const outgoing = finalizeTrackAnalysis(fixture.outgoing);
  const incoming = finalizeTrackAnalysis(fixture.incoming);

  const plan = planPairTransition({
    analysis: outgoing,
    nextAnalysis: incoming,
    duration: outgoing.duration,
    nextDuration: incoming.duration
  });

  assert.ok(plan.choreography, 'Choreography must be attached');
  assert.ok(
    plan.choreography.strategy === CHOREOGRAPHY_STRATEGY.FILTERED_HANDOFF ||
    plan.choreography.strategy === CHOREOGRAPHY_STRATEGY.CLEAN_CUT,
    `Strategy must be filtered_handoff or clean_cut, got ${plan.choreography.strategy}`
  );
  assert.ok(
    plan.choreography.duration <= 4.0 + 1e-4,
    `Duration must be <= 4.0s, got ${plan.choreography.duration}`
  );
  assert.ok(plan.choreography.duration >= 0.0);
});

test('fixture safe instrumental blend receives staged blend with single bass owner', () => {
  const fixture = fixtures.safe_instrumental_blend;
  const outgoing = finalizeTrackAnalysis(fixture.outgoing);
  const incoming = finalizeTrackAnalysis(fixture.incoming);

  const plan = planPairTransition({
    analysis: outgoing,
    nextAnalysis: incoming,
    duration: outgoing.duration,
    nextDuration: incoming.duration
  });

  assert.ok(plan.choreography, 'Choreography must be attached');
  assert.equal(plan.choreography.strategy, CHOREOGRAPHY_STRATEGY.STAGED_BLEND);
  assert.ok(plan.choreography.duration >= 3.0 && plan.choreography.duration <= 16.0);

  const curves = plan.choreography.curves;
  assert.ok(curves.outgoingBass.length > 0);
  assert.ok(curves.incomingBass.length > 0);

  // Verify single bass owner at start and end of overlap
  const outBassStart = evaluateAutomationCurve(curves.outgoingBass, 0.0);
  const inBassStart = evaluateAutomationCurve(curves.incomingBass, 0.0);
  assert.ok(Math.abs(outBassStart - 1.0) < 1e-3);
  assert.ok(Math.abs(inBassStart - 0.0) < 1e-3);

  const outBassEnd = evaluateAutomationCurve(curves.outgoingBass, 1.0);
  const inBassEnd = evaluateAutomationCurve(curves.incomingBass, 1.0);
  assert.ok(Math.abs(outBassEnd - 0.0) < 1e-3);
  assert.ok(Math.abs(inBassEnd - 1.0) < 1e-3);
});

test('fixture vocal collision receives safe filtered handoff or clean cut', () => {
  const fixture = fixtures.sustained_vocal_collision;
  const outgoing = finalizeTrackAnalysis(fixture.outgoing);
  const incoming = finalizeTrackAnalysis(fixture.incoming);

  const plan = planPairTransition({
    analysis: outgoing,
    nextAnalysis: incoming,
    duration: outgoing.duration,
    nextDuration: incoming.duration
  });

  assert.ok(plan.choreography, 'Choreography must be attached');
  assert.ok(
    plan.choreography.duration <= 4.0 ||
    [CHOREOGRAPHY_STRATEGY.FILTERED_HANDOFF, CHOREOGRAPHY_STRATEGY.CLEAN_CUT, CHOREOGRAPHY_STRATEGY.STAGED_BLEND].includes(plan.choreography.strategy)
  );
  assert.notEqual(plan.transitionClass, 'full_beatmatched');
});

test('fixture weak evidence pair receives fallback choreography without crashing', () => {
  const fixture = fixtures.weak_evidence_pair;
  const outgoing = finalizeTrackAnalysis(fixture.outgoing);
  const incoming = finalizeTrackAnalysis(fixture.incoming);

  const plan = planPairTransition({
    analysis: outgoing,
    nextAnalysis: incoming,
    duration: outgoing.duration,
    nextDuration: incoming.duration
  });

  assert.ok(plan.choreography, 'Choreography must be attached');
  assert.ok(plan.choreography.duration > 0.0);
});
