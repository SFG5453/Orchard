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

// Canonical, language-neutral evidence consumed by the desktop pair planner.
// Legacy phrase/drop fields deliberately never enter this module: a beat-count
// fallback is useful evidence, but it is not a detected musical section.

export const MAX_TRACK_FRAMES = 240;
export const RHYTHMIC_GROUP_DOWNBEATS = 4;
export const RHYTHMIC_FALLBACK_CONFIDENCE = 0.2;

const FRAME_FIELDS = [
  'energy',
  'low',
  'mid',
  'high',
  'vocal',
  'novelty',
  'transientDensity',
  'stability'
];

function finite(value) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function clamp(value, minimum = 0, maximum = 1) {
  const number = finite(value);
  return number === null ? minimum : Math.max(minimum, Math.min(maximum, number));
}

function positive(value, fallback = 0) {
  const number = finite(value);
  return number !== null && number > 0 ? number : fallback;
}

function rounded(value) {
  return Math.round(value * 1e6) / 1e6;
}

function sortedTimes(values, duration) {
  const maximum = positive(duration, Infinity);
  return [...new Set((Array.isArray(values) ? values : [])
    .map(finite)
    .filter((value) => value !== null && value >= 0 && value <= maximum)
    .map(rounded))]
    .sort((left, right) => left - right);
}

function normalizedMeter(value = {}) {
  const beatsPerBar = Math.round(positive(value.beatsPerBar, 4));
  const source = value.source === 'detected' ? 'detected' : 'assumed-4-4';
  return Object.freeze({
    beatsPerBar: Math.max(1, Math.min(16, beatsPerBar)),
    confidence: clamp(value.confidence, 0, 1),
    source
  });
}

function normalizeTiming(raw, duration) {
  const bpm = positive(raw.bpm);
  const beatInterval = positive(raw.beatInterval, bpm > 0 ? 60 / bpm : 0);
  const beatConfidence = clamp(raw.beatConfidence, 0, 1);
  return Object.freeze({
    bpm,
    beatInterval,
    beats: Object.freeze(sortedTimes(raw.beats, duration)),
    downbeats: Object.freeze(sortedTimes(raw.downbeats, duration)),
    beatConfidence,
    downbeatConfidence: clamp(raw.downbeatConfidence ?? beatConfidence, 0, 1),
    source: String(raw.beatModelChecked ? 'beat-model-refined' : raw.analysisSource || 'analysis'),
    meter: normalizedMeter(raw.meter)
  });
}

function curveValueAt(curve, target) {
  if (!Array.isArray(curve) || !curve.length) return null;
  let best = null;
  let distance = Infinity;
  for (const point of curve) {
    const time = finite(point?.time);
    const value = finite(point?.energy);
    if (time === null || value === null) continue;
    const candidateDistance = Math.abs(time - target);
    if (candidateDistance < distance) {
      best = value;
      distance = candidateDistance;
    }
  }
  return best;
}

function legacyFrames(raw) {
  const energy = Array.isArray(raw.energyCurve) ? raw.energyCurve : [];
  return energy.map((point, index) => ({
    time: point?.time,
    energy: point?.energy,
    low: curveValueAt(raw.lowEnergyCurve, Number(point?.time)),
    mid: curveValueAt(raw.midEnergyCurve, Number(point?.time)),
    high: curveValueAt(raw.highEnergyCurve, Number(point?.time)),
    vocal: finite(raw.vocalActivityMask?.[index])
  }));
}

function normalizeFrames(raw, duration) {
  const supplied = Array.isArray(raw.transitionFeatureFrames) && raw.transitionFeatureFrames.length
    ? raw.transitionFeatureFrames
    : legacyFrames(raw);
  const byTime = new Map();
  for (const item of supplied) {
    const time = finite(item?.time);
    if (time === null || time < 0 || time > duration) continue;
    const frame = { time: rounded(time) };
    for (const field of FRAME_FIELDS) frame[field] = finite(item?.[field]);
    byTime.set(frame.time, Object.freeze(frame));
  }
  const frames = [...byTime.values()].sort((left, right) => left.time - right.time);
  if (frames.length <= MAX_TRACK_FRAMES) return Object.freeze(frames);
  const stride = (frames.length - 1) / (MAX_TRACK_FRAMES - 1);
  return Object.freeze(Array.from(
    { length: MAX_TRACK_FRAMES },
    (_, index) => frames[Math.round(index * stride)]
  ));
}

function audibleRange(raw, duration) {
  const start = clamp(raw.audibleStartTime ?? raw.pickupTime, 0, duration);
  const end = clamp(raw.contentEndTime ?? duration, start, duration);
  return Object.freeze({
    start,
    end,
    confidence: clamp(raw.pickupConfidence ?? (end > start ? 0.5 : 0), 0, 1)
  });
}

function normalizeHarmonic(raw) {
  const chroma = Array.isArray(raw.chroma)
    ? raw.chroma.slice(0, 12).map(finite).map((value) => value ?? 0)
    : [];
  return Object.freeze({
    key: String(raw.key || ''),
    keyConfidence: clamp(raw.keyConfidence, 0, 1),
    chroma: Object.freeze(chroma)
  });
}

function boundaryEvidence(candidate = {}) {
  const nested = candidate.evidence && typeof candidate.evidence === 'object'
    ? candidate.evidence
    : candidate;
  return Object.freeze({
    noveltyPeak: finite(nested.noveltyPeak),
    energyDelta: finite(nested.energyDelta),
    lowDelta: finite(nested.lowDelta),
    vocalDelta: finite(nested.vocalDelta),
    stabilityBefore: finite(nested.stabilityBefore),
    stabilityAfter: finite(nested.stabilityAfter),
    downbeatDistance: finite(nested.downbeatDistance)
  });
}

function detectedBoundaries(raw, range) {
  return (Array.isArray(raw.structuralBoundaryCandidates)
    ? raw.structuralBoundaryCandidates
    : [])
    .map((candidate) => {
      const time = finite(candidate?.time);
      if (time === null || time < range.start || time > range.end) return null;
      return Object.freeze({
        time: rounded(time),
        confidence: clamp(candidate.confidence, 0, 1),
        source: 'detected-change',
        evidence: boundaryEvidence(candidate)
      });
    })
    .filter(Boolean);
}

function endpointBoundaries(range) {
  return [range.start, range.end].map((time) => Object.freeze({
    time: rounded(time),
    confidence: range.confidence,
    source: 'endpoint',
    evidence: boundaryEvidence()
  }));
}

function rhythmicFallbacks(timing, range) {
  const output = [];
  const downbeats = timing.downbeats.filter((time) => time >= range.start && time <= range.end);
  for (let index = 0; index < downbeats.length; index += RHYTHMIC_GROUP_DOWNBEATS) {
    output.push(Object.freeze({
      time: downbeats[index],
      confidence: Math.min(
        RHYTHMIC_FALLBACK_CONFIDENCE,
        timing.beatConfidence,
        timing.downbeatConfidence
      ),
      source: 'rhythmic-fallback',
      evidence: Object.freeze({
        ...boundaryEvidence(),
        downbeatDistance: 0
      })
    }));
  }
  return output;
}

function dedupeBoundaries(boundaries) {
  const priorities = new Map([
    ['detected-change', 0],
    ['endpoint', 1],
    ['rhythmic-fallback', 2]
  ]);
  const unique = new Map();
  for (const boundary of boundaries) {
    const key = `${boundary.source}:${boundary.time}`;
    const previous = unique.get(key);
    if (!previous || boundary.confidence > previous.confidence) unique.set(key, boundary);
  }
  return Object.freeze([...unique.values()].sort((left, right) =>
    left.time - right.time ||
    (priorities.get(left.source) ?? 9) - (priorities.get(right.source) ?? 9)
  ));
}

export function normalizeTrackAnalysis(raw = {}) {
  const duration = positive(raw.duration);
  const range = audibleRange(raw, duration);
  const timing = normalizeTiming(raw, duration);
  return Object.freeze({
    duration,
    audibleRange: range,
    timing,
    harmonic: normalizeHarmonic(raw),
    frames: normalizeFrames(raw, duration),
    boundaries: dedupeBoundaries([
      ...detectedBoundaries(raw, range),
      ...endpointBoundaries(range),
      ...rhythmicFallbacks(timing, range)
    ])
  });
}

export function finalizeTrackAnalysis(raw = {}) {
  return { ...raw, ...normalizeTrackAnalysis(raw) };
}

export function interpolateTrackFrame(analysis = {}, field, time) {
  if (!FRAME_FIELDS.includes(field)) return null;
  const frames = Array.isArray(analysis.frames) ? analysis.frames : [];
  const target = finite(time);
  if (!frames.length || target === null || target < frames[0].time || target > frames.at(-1).time) {
    return null;
  }
  let previous = frames[0];
  if (target === previous.time) return finite(previous[field]);
  for (let index = 1; index < frames.length; index += 1) {
    const next = frames[index];
    if (target === next.time) return finite(next[field]);
    if (target > next.time) {
      previous = next;
      continue;
    }
    const left = finite(previous[field]);
    const right = finite(next[field]);
    if (left === null || right === null || next.time <= previous.time) return null;
    const fraction = (target - previous.time) / (next.time - previous.time);
    return rounded(left + (right - left) * fraction);
  }
  return null;
}

export function summarizeTrackWindow(analysis = {}, start = 0, end = 0) {
  const from = finite(start) ?? 0;
  const to = finite(end) ?? from;
  const allFrames = Array.isArray(analysis.frames) ? analysis.frames : [];
  const frames = allFrames.filter((frame) => frame.time >= from && frame.time <= to);
  const output = {
    coverage: frames.length ? 1 : 0
  };
  for (const field of FRAME_FIELDS) {
    const values = frames.map((frame) => finite(frame[field])).filter((value) => value !== null);
    output[field] = values.length
      ? rounded(values.reduce((sum, value) => sum + value, 0) / values.length)
      : null;
  }
  return output;
}
