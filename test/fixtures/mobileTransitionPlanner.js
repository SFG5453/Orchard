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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Inputs stay raw so the desktop normalizer and Android's analysis adapter are both exercised.
function analysis(role, overrides = {}) {
  const bpm = overrides.bpm ?? 120;
  const duration = overrides.duration ?? 120;
  const interval = bpm > 0 ? 60 / bpm : 0;
  const beats = interval ? Array.from({ length: Math.floor(duration / interval) + 1 }, (_, i) => i * interval) : [];
  const boundary = role === 'outgoing' ? 112 : 16;
  return {
    status: '', trackId: '', duration, bpm, beatInterval: interval, beatConfidence: 0.92,
    firstBeat: 0, key: 'C major', keyConfidence: 0.9,
    audibleStartTime: 0, pickupTime: 0, introEndTime: 0, contentEndTime: duration,
    outroStartTime: 0, mixInTime: 0, mixOutTime: 0, vocalProbability: 0,
    downbeats: beats.filter((_, i) => i % 4 === 0), phraseBoundaries: [],
    energyCurve: [], lowEnergyCurve: [], vocalActivityMask: [], mixInCandidates: [], mixOutCandidates: [],
    beats, pickupConfidence: 0.9, downbeatConfidence: 0.92,
    meter: { beatsPerBar: 4, confidence: 0.85, source: 'detected' },
    chroma: [1, 0, 0, 0, 0.7, 0, 0, 0.5, 0, 0, 0, 0],
    structuralBoundaryCandidates: [boundary].map(time => ({ time, confidence: 0.9, noveltyPeak: 0.8 })),
    transitionFeatureFrames: Array.from({ length: 121 }, (_, time) => ({
      time, energy: role === 'outgoing' ? Math.max(0.28, 0.78 - Math.max(0, time - 96) * 0.025)
        : Math.min(0.72, 0.3 + time * 0.025),
      low: 0.45, mid: 0.58, high: 0.42, vocal: 0.08, novelty: 0.55, transientDensity: 0.18, stability: 0.88
    })),
    ...overrides
  };
}
const track = (id, extra = {}) => ({ id, title: id, subtitle: 'Artist', artist: 'Artist', album: 'Album', albumId: 'album', durationSeconds: 120, ...extra });
const base = { mode: 'smart', duration: 120, currentTrack: track('out'), nextTrack: track('in'),
  analysis: analysis('outgoing'), nextAnalysis: analysis('incoming'), fadeSeconds: 6, minFadeSeconds: 1 };
const pairs = [
  ['safe', {}],
  ['unmeasured', { analysis: {}, nextAnalysis: {} }],
  ['tempo-shift', { nextAnalysis: analysis('incoming', { bpm: 122 }) }],
  ['octave', { nextAnalysis: analysis('incoming', { bpm: 60 }) }],
  ['distant-tempo', { nextAnalysis: analysis('incoming', { bpm: 87 }) }],
  ['missing-tempo', { analysis: analysis('outgoing', { bpm: 0 }) }],
  ['weak-grid', { analysis: analysis('outgoing', { beatConfidence: 0.1 }) }],
  ['missing-evidence', { analysis: analysis('outgoing', { structuralBoundaryCandidates: [], transitionFeatureFrames: [], chroma: [], keyConfidence: 0 }) }],
  ['vocal-collision', { analysis: analysis('outgoing', { transitionFeatureFrames: base.analysis.transitionFeatureFrames.map(f => ({ ...f, vocal: 0.95 })) }),
    nextAnalysis: analysis('incoming', { transitionFeatureFrames: base.nextAnalysis.transitionFeatureFrames.map(f => ({ ...f, vocal: 0.95 })) }) }],
  ['bass-swap', { analysis: analysis('outgoing', { transitionFeatureFrames: base.analysis.transitionFeatureFrames.map(f => ({ ...f, low: 0.9 })) }),
    nextAnalysis: analysis('incoming', { transitionFeatureFrames: base.nextAnalysis.transitionFeatureFrames.map(f => ({ ...f, low: 0.9 })) }) }],
  ['silence', { analysis: analysis('outgoing', { contentEndTime: 116 }), nextAnalysis: analysis('incoming', { audibleStartTime: 2 }) }],
  ['short-incoming', { nextAnalysis: analysis('incoming', { duration: 6, contentEndTime: 6 }), nextTrack: track('in', { durationSeconds: 6 }) }],
  ['album', { albumSequential: true }],
  ['speech', { nextTrack: track('in', { title: 'Podcast Episode' }) }],
  ['album-name-is-not-content', { currentTrack: track('out', { album: 'Live' }) }],
  ['stale', { analysis: analysis('outgoing', { status: 'ready', trackId: 'wrong' }) }],
  ['pending', { nextAnalysis: analysis('incoming', { status: 'pending' }) }],
  ['short-outgoing', { duration: 30, currentTrack: track('out', { durationSeconds: 30 }) }],
  ['standard', { mode: 'standard' }],
  ['standard-clamped', { mode: 'standard', fadeSeconds: 100 }],
  ['no-duration', { duration: 0, currentTrack: track('out', { durationSeconds: 0 }) }],
];
export const mobileTransitionCases = pairs.flatMap(([name, changes]) =>
  [0, 103, 111.96, 116, 119.8, 120].map(currentTime => ({
    name: `${name}@${currentTime}`, input: { ...base, ...changes, currentTime }
  })));
