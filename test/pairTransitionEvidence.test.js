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
  MAX_DETAILED_CANDIDATES,
  MAX_ROLE_CANDIDATES,
  buildCandidatePairs,
  generateTransitionCandidates,
  harmonicEvidence,
  mappedVocalCollision,
  tempoFit
} from '../src/audio/crossfade/pairTransitionEvidence.js';

function analysis({
  duration = 120,
  bpm = 120,
  beatConfidence = 0.9,
  key = 'C major',
  keyConfidence = 0.9,
  chroma = [1, 0, 0, 0, 0.7, 0, 0, 0.5, 0, 0, 0, 0],
  boundaries = [],
  vocal = () => 0.1,
  energy = () => 0.6,
  stability = () => 0.8,
  novelty = () => 0.2
} = {}) {
  const beatInterval = 60 / bpm;
  const beats = [];
  const downbeats = [];
  for (let time = 0, index = 0; time <= duration; time += beatInterval, index += 1) {
    beats.push(Number(time.toFixed(6)));
    if (index % 4 === 0) downbeats.push(Number(time.toFixed(6)));
  }
  const transitionFeatureFrames = [];
  for (let time = 0; time <= duration; time += 0.5) {
    transitionFeatureFrames.push({
      time,
      energy: energy(time),
      low: 0.5,
      mid: 0.6,
      high: 0.4,
      ...(typeof vocal === 'function' ? { vocal: vocal(time) } : {}),
      novelty: novelty(time),
      transientDensity: 0.2,
      stability: stability(time)
    });
  }
  return normalizeTrackAnalysis({
    duration,
    bpm,
    beatInterval,
    beatConfidence,
    downbeatConfidence: beatConfidence,
    beats,
    downbeats,
    audibleStartTime: 0,
    contentEndTime: duration,
    key,
    keyConfidence,
    chroma,
    transitionFeatureFrames,
    structuralBoundaryCandidates: boundaries,
    meter: { beatsPerBar: 4, confidence: 0.8, source: 'detected' }
  });
}

test('folds half-time tempos without demanding a two-times stretch', () => {
  assert.deepEqual(tempoFit(87, 174), {
    outgoingBpm: 87,
    incomingBpm: 87,
    targetBpm: 87,
    outgoingRatio: 1,
    incomingRatio: 1,
    deviation: 0,
    beatmatched: true
  });
  assert.equal(tempoFit(120, 132).beatmatched, false);
});

test('distinguishes compatible keys from a trusted severe clash', () => {
  const outgoing = analysis({ key: 'C major', keyConfidence: 0.9 });
  const fifth = analysis({ key: 'G major', keyConfidence: 0.9 });
  const tritone = analysis({
    key: 'F♯ major',
    keyConfidence: 0.9,
    chroma: [0, 1, 0, 0, 0, 0, 0.8, 0, 0, 0, 0.5, 0]
  });
  const uncertainTritone = analysis({
    key: 'F♯ major',
    keyConfidence: 0.2,
    chroma: tritone.harmonic.chroma
  });

  assert.ok(harmonicEvidence(outgoing, fifth).score > 0.7);
  assert.equal(harmonicEvidence(outgoing, fifth).severeClash, false);
  assert.ok(harmonicEvidence(outgoing, tritone).score < 0.2);
  assert.equal(harmonicEvidence(outgoing, tritone).severeClash, true);
  assert.equal(harmonicEvidence(outgoing, uncertainTritone).severeClash, false);
});

test('measures vocal collision on the stretched candidate timeline', () => {
  const outgoing = analysis({
    duration: 30,
    vocal: (time) => time >= 4 && time <= 13 ? 0.9 : 0.1
  });
  const incoming = analysis({
    duration: 30,
    vocal: (time) => time >= 2 && time <= 10 ? 0.85 : 0.1
  });

  const collision = mappedVocalCollision(outgoing, incoming, {
    outgoingStart: 4,
    incomingStart: 2,
    durationSeconds: 8,
    outgoingRatio: 1.04,
    incomingRatio: 1,
    targetBpm: 120
  });

  assert.ok(collision.longestRunSeconds >= 7.5);
  assert.ok(collision.activeFraction > 0.9);
  assert.ok(collision.simultaneousMean > 0.8);
  assert.ok(collision.coverage > 0.95);
});

test('keeps a brief vocal crossing distinct from sustained collision', () => {
  const outgoing = analysis({
    duration: 20,
    vocal: (time) => time >= 4 && time <= 12 ? 0.9 : 0.1
  });
  const incoming = analysis({
    duration: 20,
    vocal: (time) => time >= 9.5 && time <= 10 ? 0.9 : 0.1
  });

  const collision = mappedVocalCollision(outgoing, incoming, {
    outgoingStart: 4,
    incomingStart: 2,
    durationSeconds: 8,
    outgoingRatio: 1,
    incomingRatio: 1,
    targetBpm: 120
  });

  assert.ok(collision.longestRunSeconds <= 0.75);
  assert.ok(collision.activeFraction < 0.15);
});

test('does not turn missing vocal samples into silence', () => {
  const outgoing = analysis({ duration: 20, vocal: null });
  const incoming = analysis({ duration: 20, vocal: null });

  const collision = mappedVocalCollision(outgoing, incoming, {
    outgoingStart: 4,
    incomingStart: 2,
    durationSeconds: 8,
    outgoingRatio: 1,
    incomingRatio: 1,
    targetBpm: 120
  });

  assert.equal(collision.coverage, 0);
  assert.equal(collision.simultaneousMean, null);
  assert.equal(collision.activeFraction, null);
});

test('keeps credible late incoming cues and caps each candidate side', () => {
  const detected = Array.from({ length: 30 }, (_, index) => ({
    time: index * 2 + 1,
    confidence: 0.4 + index / 100,
    source: 'detected-change',
    noveltyPeak: 0.5,
    energyDelta: 0.4,
    stabilityBefore: 0.8,
    stabilityAfter: 0.8,
    downbeatDistance: 0
  }));
  const track = analysis({ duration: 180, boundaries: detected });
  const candidates = generateTransitionCandidates(track, 'incoming');

  assert.equal(candidates.length, MAX_ROLE_CANDIDATES);
  assert.ok(candidates.some((candidate) => candidate.anchorTime >= 30));
  assert.deepEqual(
    candidates,
    generateTransitionCandidates(track, 'incoming'),
    'candidate generation must be deterministic'
  );
  assert.ok(candidates.every((candidate) => !candidate.id.includes('drop')));
});

test('prunes the cue cross-product to a deterministic detailed-search budget', () => {
  const boundaries = Array.from({ length: 24 }, (_, index) => ({
    time: index * 4 + 8,
    confidence: 0.8,
    source: 'detected-change',
    noveltyPeak: 0.8,
    energyDelta: 0.6,
    stabilityBefore: 0.8,
    stabilityAfter: 0.8,
    downbeatDistance: 0
  }));
  const outgoing = analysis({ duration: 180, boundaries });
  const incoming = analysis({ duration: 180, boundaries });
  const outgoingCandidates = generateTransitionCandidates(outgoing, 'outgoing');
  const incomingCandidates = generateTransitionCandidates(incoming, 'incoming');
  const fit = tempoFit(120, 120);
  const result = buildCandidatePairs(
    outgoingCandidates,
    incomingCandidates,
    fit,
    { outgoingAnalysis: outgoing, incomingAnalysis: incoming }
  );

  assert.equal(result.finalists.length, MAX_DETAILED_CANDIDATES);
  assert.ok(result.diagnostics.combinations > MAX_DETAILED_CANDIDATES);
  assert.equal(result.diagnostics.detailed, MAX_DETAILED_CANDIDATES);
  assert.deepEqual(
    result.finalists,
    buildCandidatePairs(
      outgoingCandidates,
      incomingCandidates,
      fit,
      { outgoingAnalysis: outgoing, incomingAnalysis: incoming }
    ).finalists
  );
});

test('allows a 32-beat option only for strong low-vocal structural evidence', () => {
  const strong = analysis({
    duration: 180,
    boundaries: [
      { time: 120, confidence: 0.9, source: 'detected-change', noveltyPeak: 0.9 },
      { time: 32, confidence: 0.9, source: 'detected-change', noveltyPeak: 0.9 }
    ],
    vocal: () => 0.1
  });
  const weak = analysis({
    duration: 180,
    boundaries: [
      { time: 120, confidence: 0.3, source: 'detected-change', noveltyPeak: 0.3 },
      { time: 32, confidence: 0.3, source: 'detected-change', noveltyPeak: 0.3 }
    ],
    vocal: () => 0.8
  });
  const fit = tempoFit(120, 120);
  const strongPairs = buildCandidatePairs(
    generateTransitionCandidates(strong, 'outgoing'),
    generateTransitionCandidates(strong, 'incoming'),
    fit,
    { outgoingAnalysis: strong, incomingAnalysis: strong }
  ).finalists;
  const weakPairs = buildCandidatePairs(
    generateTransitionCandidates(weak, 'outgoing'),
    generateTransitionCandidates(weak, 'incoming'),
    fit,
    { outgoingAnalysis: weak, incomingAnalysis: weak }
  ).finalists;

  assert.ok(strongPairs.some((candidate) => candidate.beats === 32));
  assert.ok(weakPairs.every((candidate) => candidate.beats !== 32));
});
