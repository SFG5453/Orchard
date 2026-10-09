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

// Worker-shaped analysis: 0.25 s bass and vocal curves over 1 s frames.
export function raw({
  duration = 120,
  bpm = 120,
  beatFrom = 0,
  beatUntil = Infinity,
  vocal = () => 0.1,
  sub = 0,
  boundaries = []
} = {}) {
  const beatInterval = 60 / bpm;
  const beats = [];
  const downbeats = [];
  for (let time = 0, index = 0; time <= duration; time += beatInterval, index += 1) {
    beats.push(Number(time.toFixed(6)));
    if (index % 4 === 0) downbeats.push(Number(time.toFixed(6)));
  }
  const centres = Array.from({ length: Math.floor(duration / 0.25) }, (_, index) => index * 0.25 + 0.125);
  return {
    duration, bpm, beatInterval, beats, downbeats,
    beatConfidence: 0.92, downbeatConfidence: 0.92,
    audibleStartTime: 0, pickupConfidence: 0.9, contentEndTime: duration,
    key: 'C major', keyConfidence: 0.9, chroma: [1, 0, 0, 0, 0.7, 0, 0, 0.5, 0, 0, 0, 0],
    transitionFeatureFrames: Array.from({ length: duration + 1 }, (_, time) => ({
      time, energy: 0.7, low: 0.5, mid: 0.5, high: 0.4, vocal: 0.9,
      novelty: 0.5, transientDensity: 0.2, stability: 0.9
    })),
    structuralBoundaryCandidates: boundaries.map((time) => ({
      time, confidence: 0.9, source: 'detected-change', noveltyPeak: 0.6, energyDelta: 0.6,
      lowDelta: 0.3, vocalDelta: 0.2, stabilityBefore: 0.9, stabilityAfter: 0.9, downbeatDistance: 0
    })),
    meter: { beatsPerBar: 4, confidence: 0.85, source: 'detected' },
    bassCurve: { start: 0, step: 0.25, values: centres.map((t) => (t >= beatFrom && t < beatUntil ? 1 : 0.02)) },
    vocalCurve: { start: 0, step: 0.25, values: centres.map(vocal) },
    subBassRatio: sub
  };
}
