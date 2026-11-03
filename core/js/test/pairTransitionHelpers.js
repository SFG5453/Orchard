/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

import { normalizeTrackAnalysis } from '../shared/trackAnalysis.js';

// Synthetic track analyses shared by the pair transition planner tests.
const ROOT = new Map([
  ['C', 0], ['C♯', 1], ['D', 2], ['E♭', 3], ['E', 4], ['F', 5],
  ['F♯', 6], ['G', 7], ['A♭', 8], ['A', 9], ['B♭', 10], ['B', 11]
]);

function chromaFor(key) {
  const [name, mode] = String(key).split(' ');
  const root = ROOT.get(name);
  if (!Number.isInteger(root)) return [];
  const third = mode === 'minor' ? 3 : 4;
  const values = Array(12).fill(0);
  values[root] = 1;
  values[(root + third) % 12] = 0.7;
  values[(root + 7) % 12] = 0.5;
  return values;
}

export function track({
  role = 'outgoing',
  duration = 120,
  bpm = 120,
  beatConfidence = 0.92,
  key = 'C major',
  keyConfidence = 0.9,
  chroma = chromaFor(key),
  audibleStart = 0,
  contentEnd = duration,
  boundaryTimes = role === 'outgoing' ? [104, 112] : [16, 32],
  boundaryConfidence = 0.9,
  downbeats,
  vocal = () => 0.08,
  energy,
  low = () => 0.45,
  stability = () => 0.88,
  novelty = () => 0.55,
  meterConfidence = 0.85
} = {}) {
  const beatInterval = 60 / bpm;
  const beats = [];
  const generatedDownbeats = [];
  for (let time = 0, index = 0; time <= duration; time += beatInterval, index += 1) {
    beats.push(Number(time.toFixed(6)));
    if (index % 4 === 0) generatedDownbeats.push(Number(time.toFixed(6)));
  }
  const finalDownbeats = downbeats === undefined ? generatedDownbeats : downbeats;
  const defaultEnergy = role === 'outgoing'
    ? (time) => time < 96 ? 0.78 : Math.max(0.28, 0.78 - (time - 96) * 0.025)
    : (time) => time < 16 ? 0.3 + time * 0.025 : 0.72;
  const transitionFeatureFrames = [];
  for (let time = 0; time <= duration; time += 0.5) {
    transitionFeatureFrames.push({
      time,
      energy: (energy || defaultEnergy)(time),
      low: low(time),
      mid: 0.58,
      high: 0.42,
      ...(typeof vocal === 'function' ? { vocal: vocal(time) } : {}),
      novelty: novelty(time),
      transientDensity: 0.18,
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
    downbeats: finalDownbeats,
    audibleStartTime: audibleStart,
    pickupConfidence: 0.9,
    contentEndTime: contentEnd,
    key,
    keyConfidence,
    chroma,
    transitionFeatureFrames,
    structuralBoundaryCandidates: boundaryTimes.map((time, index) => ({
      time,
      confidence: Array.isArray(boundaryConfidence)
        ? boundaryConfidence[index]
        : boundaryConfidence,
      source: 'detected-change',
      noveltyPeak: novelty(time),
      energyDelta: 0.6,
      lowDelta: 0.3,
      vocalDelta: 0.2,
      stabilityBefore: stability(Math.max(0, time - 2)),
      stabilityAfter: stability(Math.min(duration, time + 2)),
      downbeatDistance: 0
    })),
    meter: {
      beatsPerBar: 4,
      confidence: meterConfidence,
      source: meterConfidence >= 0.5 ? 'detected' : 'assumed-4-4'
    }
  });
}

export function cleanPair(overrides = {}) {
  return {
    analysis: track({ role: 'outgoing', ...(overrides.outgoing || {}) }),
    nextAnalysis: track({ role: 'incoming', ...(overrides.incoming || {}) }),
    duration: overrides.outgoing?.duration || 120,
    nextDuration: overrides.incoming?.duration || 120
  };
}

export function reasonCount(plan, reason) {
  return Number(plan.diagnostics?.reasonCounts?.[reason]) || 0;
}
