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
  AUDIO_ANALYSIS_VERSION,
  isValidLocalAnalysis
} from '../shared/audioAnalysis.js';
import {
  finalizeTrackAnalysis,
  interpolateTrackFrame,
  normalizeTrackAnalysis,
  summarizeTrackWindow
} from '../shared/trackAnalysis.js';

function rawAnalysis(overrides = {}) {
  const beats = Array.from({ length: 41 }, (_, index) => index * 0.5);
  return {
    analysisVersion: 12,
    duration: 20,
    bpm: 120,
    beatInterval: 0.5,
    beatConfidence: 0.8,
    beats,
    downbeats: [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20],
    audibleStartTime: 0,
    contentEndTime: 20,
    key: 'C major',
    keyConfidence: 0.7,
    chroma: [1, 0, 0, 0, 0.3, 0, 0, 0.2, 0, 0, 0, 0],
    transitionFeatureFrames: [
      { time: 0, energy: 0.2, low: 0.1, mid: 0.2, high: 0.1, novelty: 0.1, stability: 0.9 },
      { time: 10, energy: 0.7, low: 0.6, mid: 0.7, high: 0.5, vocal: 0.8, novelty: 0.8, stability: 0.4 },
      { time: 20, energy: 0.4, low: 0.3, mid: 0.4, high: 0.2, novelty: 0.2, stability: 0.8 }
    ],
    structuralBoundaryCandidates: [],
    meter: { beatsPerBar: 4, confidence: 0.15, source: 'assumed-4-4' },
    ...overrides
  };
}

test('rebuilds rhythmic fallbacks from the final downbeat grid', () => {
  const raw = rawAnalysis({
    duration: 16,
    contentEndTime: 16,
    downbeats: [0, 2, 4, 6, 8, 10, 12, 14, 16],
    phraseBoundaries: [0, 16]
  });
  const first = finalizeTrackAnalysis(raw);
  const refined = finalizeTrackAnalysis({
    ...raw,
    downbeats: [1, 3, 5, 7, 9, 11, 13, 15]
  });

  assert.deepEqual(
    first.boundaries
      .filter((item) => item.source === 'rhythmic-fallback')
      .map((item) => item.time),
    [0, 8, 16]
  );
  assert.deepEqual(
    refined.boundaries
      .filter((item) => item.source === 'rhythmic-fallback')
      .map((item) => item.time),
    [1, 9]
  );
  assert.ok(refined.boundaries.every((item) => !('type' in item)));
});

test('retains measured boundary evidence without promoting a semantic label', () => {
  const analysis = normalizeTrackAnalysis(rawAnalysis({
    structuralBoundaryCandidates: [{
      time: 10,
      observedTime: 10.1,
      confidence: 0.82,
      source: 'detected-change',
      noveltyPeak: 0.9,
      energyDelta: 0.7,
      lowDelta: 0.4,
      vocalDelta: 0.3,
      stabilityBefore: 0.8,
      stabilityAfter: 0.7,
      downbeatDistance: 0.1,
      type: 'main_drop'
    }]
  }));

  const boundary = analysis.boundaries.find((item) => item.source === 'detected-change');
  assert.equal(boundary.time, 10);
  assert.equal(boundary.confidence, 0.82);
  assert.equal(boundary.evidence.noveltyPeak, 0.9);
  assert.equal(boundary.evidence.energyDelta, 0.7);
  assert.equal(boundary.evidence.observedTime, 10.1);
  assert.equal(boundary.evidence.downbeatDistance, 0.1);
  assert.equal('type' in boundary, false);
});

test('re-snaps detected changes against the final refined downbeat grid', () => {
  const candidate = {
    // Native first snapped this observation to the old 10-second downbeat.
    time: 10,
    observedTime: 10.4,
    confidence: 0.82,
    noveltyPeak: 0.9,
    downbeatDistance: 0.4
  };
  const before = normalizeTrackAnalysis(rawAnalysis({
    downbeats: [0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20],
    structuralBoundaryCandidates: [candidate]
  }));
  const refined = normalizeTrackAnalysis(rawAnalysis({
    downbeats: [0.5, 2.5, 4.5, 6.5, 8.5, 10.5, 12.5, 14.5, 16.5, 18.5],
    beatModelChecked: true,
    structuralBoundaryCandidates: [candidate]
  }));

  const detected = (analysis) => analysis.boundaries.find(
    (boundary) => boundary.source === 'detected-change'
  );
  assert.equal(detected(before).time, 10);
  assert.equal(detected(before).evidence.downbeatDistance, 0.4);
  assert.equal(detected(refined).time, 10.5);
  assert.equal(detected(refined).evidence.downbeatDistance, 0.1);
  assert.equal(detected(refined).evidence.observedTime, 10.4);
});

test('keeps unavailable vocal evidence unknown during interpolation', () => {
  const analysis = normalizeTrackAnalysis(rawAnalysis());

  assert.equal(interpolateTrackFrame(analysis, 'vocal', 2), null);
  assert.equal(interpolateTrackFrame(analysis, 'energy', 5), 0.45);
  assert.equal(interpolateTrackFrame(analysis, 'vocal', 10), 0.8);
});

test('summarizes only known values inside the requested window', () => {
  const analysis = normalizeTrackAnalysis(rawAnalysis({
    transitionFeatureFrames: [
      { time: 0, energy: 0.2, vocal: 0.1, stability: 0.9 },
      { time: 5, energy: 0.4, stability: 0.7 },
      { time: 10, energy: 0.8, vocal: 0.9, stability: 0.3 },
      { time: 15, energy: 0.6, vocal: 0.7, stability: 0.5 }
    ]
  }));

  assert.deepEqual(summarizeTrackWindow(analysis, 5, 15), {
    coverage: 1,
    energy: 0.6,
    low: null,
    mid: null,
    high: null,
    vocal: 0.8,
    novelty: null,
    transientDensity: null,
    stability: 0.5
  });
});

test('sorts, clips, and deduplicates timing arrays without mutating raw analysis', () => {
  const raw = rawAnalysis({
    beats: [4, -1, 2, 2, 25, 0],
    downbeats: [8, 0, 4, 4, 30]
  });
  const before = structuredClone(raw);
  const analysis = normalizeTrackAnalysis(raw);

  assert.deepEqual(analysis.timing.beats, [0, 2, 4]);
  assert.deepEqual(analysis.timing.downbeats, [0, 4, 8]);
  assert.deepEqual(raw, before);
});

test('accepts only current canonical analysis in the persisted cache contract', () => {
  const legacy = finalizeTrackAnalysis(rawAnalysis({ analysisVersion: 11 }));
  const current = finalizeTrackAnalysis(rawAnalysis({ analysisVersion: 12 }));

  assert.equal(isValidLocalAnalysis(legacy), false);
  assert.equal(isValidLocalAnalysis(current), true);
  assert.equal(AUDIO_ANALYSIS_VERSION, 12);
});
