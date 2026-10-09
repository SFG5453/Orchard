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
import { normalizeTrackAnalysis } from '../shared/trackAnalysis.js';
import {
  findOutgoingLoop,
  localGrid,
  loopSource,
  loopedAnalysis
} from '../src/audio/crossfade/outgoingLoop.js';
import { planPairTransition } from '../src/audio/crossfade/pairTransitionPlanner.js';
import { beatPresence } from '../src/audio/crossfade/transitionFlow.js';
import { raw } from './fixtures/workerAnalysis.js';

// 120 BPM: a bar is 2 s. The beat stops at 96 s and a quiet outro runs to 120 s.
const singer = (t) => (t < 90 ? 0.9 : 0.1);
const fading = () => normalizeTrackAnalysis(raw({ beatUntil: 96, vocal: singer }));

test('the local grid irons out tracker wobble', () => {
  const wobbly = raw();
  wobbly.beats = wobbly.beats.map((time, index) => time + (index % 2 ? 0.02 : -0.02));
  const grid = localGrid(normalizeTrackAnalysis(wobbly), 50);
  assert.ok(Math.abs(grid.interval - 0.5) < 0.005);
  assert.ok(Math.abs(grid.at(grid.centre) - 50) < 0.03);
});

test('a song whose beat stops early offers its last clean bars as a loop', () => {
  const loop = findOutgoingLoop(fading());
  assert.equal(loop.beats, 8);
  assert.ok(Math.abs(loop.end - 96) < 0.01);
  assert.ok(Math.abs(loop.start - 92) < 0.01);
});

test('no loop when the beat runs to the end or the bars carry a voice', () => {
  assert.equal(findOutgoingLoop(normalizeTrackAnalysis(raw())), null);
  const sung = normalizeTrackAnalysis(raw({ beatUntil: 96, vocal: () => 0.9 }));
  assert.equal(findOutgoingLoop(sung), null);
  // Bars outside the decoded window cannot be replayed; one bar still fits here.
  assert.equal(findOutgoingLoop(fading(), 94).beats, 4);
  assert.equal(findOutgoingLoop(fading(), 95), null);
});

test('a phrase that ends inside the bars rules them out', () => {
  // The voice fades out a quarter of the way into the two-bar loop; only the last bar is clean.
  const trailing = normalizeTrackAnalysis(raw({ beatUntil: 96, vocal: (t) => (t < 92.5 ? 0.6 : 0.05) }));
  const loop = findOutgoingLoop(trailing);
  assert.equal(loop.beats, 4);
  assert.ok(Math.abs(loop.start - 94) < 0.01);
});

test('the looped timeline keeps the beat going past the loop end', () => {
  const analysis = fading();
  const loop = findOutgoingLoop(analysis);
  const looped = loopedAnalysis(analysis, loop);
  assert.ok(beatPresence(analysis, 100) < 0.1);
  assert.ok(beatPresence(looped, 100) > 0.9);
  assert.ok(Math.abs(loopSource(loop, 101) - 93) < 0.01);
  assert.ok(Math.abs(looped.duration - (loop.end + 8)) < 0.01);
  assert.ok(looped.timing.downbeats.some((time) => Math.abs(time - 100) < 0.01));
  assert.ok(looped.boundaries.every((boundary) => boundary.time < loop.end));
});

test('a mix that would lose the beat rides the loop instead', () => {
  // The rapper stops one bar before the beat does, and the new song opens singing:
  // a blend before 112 s doubles the voices, one after it drops the beat.
  const plan = planPairTransition({
    analysis: raw({ beatUntil: 112, vocal: (t) => (t < 110 ? 0.9 : 0.1) }),
    nextAnalysis: raw({ vocal: () => 0.9, boundaries: [16, 32] }),
    duration: 120, nextDuration: 120, tempoRamp: true
  });
  assert.equal(plan.renderMode, 'native');
  assert.deepEqual(plan.outgoingLoop, { start: 110, end: 112, beats: 4 });
  assert.ok(plan.outgoing.end > plan.outgoingLoop.end);
  assert.ok(plan.diagnostics.selected.looped);
  // The live fallback never plays loop time.
  assert.ok(plan.fallback.outgoingEnd <= 120);
});
