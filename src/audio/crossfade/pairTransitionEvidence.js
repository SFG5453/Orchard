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

import {
  interpolateTrackFrame,
  summarizeTrackWindow
} from '../../../shared/trackAnalysis.js';

export const MAX_ROLE_CANDIDATES = 12;
export const MAX_DETAILED_CANDIDATES = 64;
export const MAX_STRETCH_DEVIATION = 0.04;
export const VOCAL_ACTIVE_THRESHOLD = 0.6;
export const ORDINARY_BEAT_LENGTHS = Object.freeze([4, 8, 16]);

const ROOTS = new Map([
  ['C', 0], ['B#', 0],
  ['C#', 1], ['C♯', 1], ['DB', 1], ['D♭', 1],
  ['D', 2],
  ['D#', 3], ['D♯', 3], ['EB', 3], ['E♭', 3],
  ['E', 4], ['FB', 4],
  ['F', 5], ['E#', 5],
  ['F#', 6], ['F♯', 6], ['GB', 6], ['G♭', 6],
  ['G', 7],
  ['G#', 8], ['G♯', 8], ['AB', 8], ['A♭', 8],
  ['A', 9],
  ['A#', 10], ['A♯', 10], ['BB', 10], ['B♭', 10],
  ['B', 11], ['CB', 11]
]);

const SOURCE_PRIORITY = new Map([
  ['detected-change', 4],
  ['endpoint', 3],
  ['downbeat-evidence', 2],
  ['rhythmic-fallback', 1]
]);

function finite(value) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function clamp(value, minimum = 0, maximum = 1) {
  const number = finite(value);
  return number === null ? minimum : Math.max(minimum, Math.min(maximum, number));
}

function rounded(value, places = 6) {
  const scale = 10 ** places;
  return Math.round(value * scale) / scale;
}

function ratioDeviation(...ratios) {
  return Math.max(...ratios.map((ratio) => Math.abs(ratio - 1)));
}

export function tempoFit(outgoingBpm, incomingBpm) {
  const outgoing = finite(outgoingBpm) ?? 0;
  let incoming = finite(incomingBpm) ?? 0;
  if (!(outgoing > 0) || !(incoming > 0)) {
    return {
      outgoingBpm: outgoing,
      incomingBpm: incoming,
      targetBpm: outgoing,
      outgoingRatio: 1,
      incomingRatio: 1,
      deviation: 0,
      beatmatched: false
    };
  }
  while (incoming / outgoing > 1.5) incoming /= 2;
  while (incoming / outgoing < 0.67) incoming *= 2;
  incoming = rounded(incoming);
  const outgoingRatio = incoming / outgoing;
  const incomingRatio = 1;
  const deviation = ratioDeviation(outgoingRatio, incomingRatio);
  if (deviation > MAX_STRETCH_DEVIATION) {
    return {
      outgoingBpm: outgoing,
      incomingBpm: incoming,
      targetBpm: outgoing,
      outgoingRatio: 1,
      incomingRatio: 1,
      deviation: rounded(deviation),
      beatmatched: false
    };
  }
  return {
    outgoingBpm: outgoing,
    incomingBpm: incoming,
    targetBpm: incoming,
    outgoingRatio: rounded(outgoingRatio),
    incomingRatio,
    deviation: rounded(deviation),
    beatmatched: true
  };
}

function parsedKey(value) {
  const match = String(value || '').trim().match(/^([^\s]+)\s+(major|minor)$/i);
  if (!match) return null;
  const root = ROOTS.get(match[1].toUpperCase()) ?? ROOTS.get(match[1]);
  return Number.isInteger(root) ? { root, mode: match[2].toLowerCase() } : null;
}

function keyRelationship(left, right) {
  if (!left || !right) return null;
  const clockwise = (right.root - left.root + 12) % 12;
  const distance = Math.min(clockwise, 12 - clockwise);
  if (left.mode !== right.mode) {
    const relative = (left.mode === 'major' && clockwise === 9) ||
      (left.mode === 'minor' && clockwise === 3);
    if (relative) return 0.95;
    if (distance === 0) return 0.7;
    if (distance === 5) return 0.55;
    if (distance === 6) return 0.05;
    return 0.35;
  }
  if (distance === 0) return 1;
  if (distance === 5) return 0.85;
  if (distance === 2) return 0.65;
  if (distance === 1) return 0.3;
  if (distance === 6) return 0;
  return 0.45;
}

function chromaSimilarity(left, right) {
  if (!Array.isArray(left) || left.length !== 12 || !Array.isArray(right) || right.length !== 12) {
    return null;
  }
  let dot = 0;
  let leftPower = 0;
  let rightPower = 0;
  for (let index = 0; index < 12; index += 1) {
    const a = finite(left[index]) ?? 0;
    const b = finite(right[index]) ?? 0;
    dot += a * b;
    leftPower += a * a;
    rightPower += b * b;
  }
  if (leftPower <= 0 || rightPower <= 0) return null;
  return clamp(dot / Math.sqrt(leftPower * rightPower), 0, 1);
}

export function harmonicEvidence(outgoing = {}, incoming = {}) {
  const left = outgoing.harmonic || {};
  const right = incoming.harmonic || {};
  const keyScore = keyRelationship(parsedKey(left.key), parsedKey(right.key));
  const chromaScore = chromaSimilarity(left.chroma, right.chroma);
  const score = keyScore !== null && chromaScore !== null
    ? keyScore * 0.7 + chromaScore * 0.3
    : keyScore ?? chromaScore ?? 0.5;
  const confidence = Math.sqrt(
    clamp(left.keyConfidence, 0, 1) * clamp(right.keyConfidence, 0, 1)
  );
  return {
    score: rounded(score),
    confidence: rounded(confidence),
    keyScore: keyScore === null ? null : rounded(keyScore),
    chromaScore: chromaScore === null ? null : rounded(chromaScore),
    severeClash: confidence >= 0.65 && score < 0.2
  };
}

function frameCadence(analysis) {
  const frames = Array.isArray(analysis?.frames) ? analysis.frames : [];
  const differences = [];
  for (let index = 1; index < frames.length; index += 1) {
    const difference = frames[index].time - frames[index - 1].time;
    if (difference > 0) differences.push(difference);
  }
  differences.sort((left, right) => left - right);
  return differences.length ? differences[Math.floor(differences.length / 2)] : 0.25;
}

export function mappedVocalCollision(outgoing, incoming, mapping = {}) {
  const duration = Math.max(0, finite(mapping.durationSeconds) ?? 0);
  const outgoingStart = finite(mapping.outgoingStart) ?? 0;
  const incomingStart = finite(mapping.incomingStart) ?? 0;
  const outgoingRatio = finite(mapping.outgoingRatio) ?? 1;
  const incomingRatio = finite(mapping.incomingRatio) ?? 1;
  const targetBpm = finite(mapping.targetBpm) ?? 0;
  const step = Math.max(0.05, Math.min(0.25, frameCadence(outgoing), frameCadence(incoming)));
  const sampleCount = Math.max(1, Math.ceil(duration / step) + 1);
  let known = 0;
  let simultaneousTotal = 0;
  let active = 0;
  let currentRun = 0;
  let longestRun = 0;
  for (let index = 0; index < sampleCount; index += 1) {
    const time = Math.min(duration, index * step);
    const outgoingVocal = interpolateTrackFrame(
      outgoing,
      'vocal',
      outgoingStart + time * outgoingRatio
    );
    const incomingVocal = interpolateTrackFrame(
      incoming,
      'vocal',
      incomingStart + time * incomingRatio
    );
    if (outgoingVocal === null || incomingVocal === null) {
      currentRun = 0;
      continue;
    }
    known += 1;
    simultaneousTotal += Math.min(outgoingVocal, incomingVocal);
    if (outgoingVocal >= VOCAL_ACTIVE_THRESHOLD && incomingVocal >= VOCAL_ACTIVE_THRESHOLD) {
      active += 1;
      currentRun += 1;
      longestRun = Math.max(longestRun, currentRun);
    } else {
      currentRun = 0;
    }
  }
  const longestRunSeconds = Math.min(duration, longestRun * step);
  return {
    simultaneousMean: known ? rounded(simultaneousTotal / known) : null,
    activeFraction: known ? rounded(active / known) : null,
    longestRunSeconds: rounded(longestRunSeconds),
    longestRunBeats: targetBpm > 0 ? rounded(longestRunSeconds * targetBpm / 60) : 0,
    coverage: rounded(known / sampleCount)
  };
}

function candidateWindow(analysis, role) {
  const range = analysis.audibleRange || { start: 0, end: analysis.duration || 0 };
  if (role === 'incoming') {
    return {
      start: range.start,
      end: Math.min(range.end, range.start + Math.min(60, analysis.duration * 0.35))
    };
  }
  return {
    start: Math.max(range.start, range.end - Math.min(60, analysis.duration * 0.4)),
    end: range.end
  };
}

function summaryFor(analysis, role, anchorTime) {
  const range = analysis.audibleRange || { start: 0, end: analysis.duration || 0 };
  const seconds = 16;
  return role === 'incoming'
    ? summarizeTrackWindow(analysis, Math.max(range.start, anchorTime - seconds), anchorTime)
    : summarizeTrackWindow(analysis, Math.max(range.start, anchorTime - seconds), anchorTime);
}

function candidatePriority(candidate) {
  const source = SOURCE_PRIORITY.get(candidate.source) ?? 0;
  const stability = finite(candidate.summary?.stability) ?? 0.5;
  const vocal = finite(candidate.summary?.vocal);
  const clean = vocal === null ? 0.35 : 1 - clamp(vocal);
  return source * 0.2 + candidate.confidence * 0.55 + stability * 0.15 + clean * 0.1;
}

function candidateFrom(analysis, role, boundary) {
  const anchorTime = finite(boundary?.time);
  if (anchorTime === null) return null;
  const range = analysis.audibleRange;
  const summary = summaryFor(analysis, role, anchorTime);
  const candidate = {
    id: `${role}:${boundary.source}:${anchorTime.toFixed(6)}`,
    role,
    anchorTime,
    source: String(boundary.source || 'rhythmic-fallback'),
    confidence: clamp(boundary.confidence, 0, 1),
    runwaySeconds: rounded(anchorTime - range.start),
    summary,
    evidence: boundary.evidence || null
  };
  return { ...candidate, priorityScore: rounded(candidatePriority(candidate)) };
}

function dedupeCandidates(candidates, tolerance, role) {
  const ordered = [...candidates].sort((left, right) =>
    right.priorityScore - left.priorityScore ||
    (SOURCE_PRIORITY.get(right.source) ?? 0) - (SOURCE_PRIORITY.get(left.source) ?? 0) ||
    (role === 'incoming'
      ? left.anchorTime - right.anchorTime
      : right.anchorTime - left.anchorTime) ||
    left.id.localeCompare(right.id)
  );
  const output = [];
  for (const candidate of ordered) {
    if (output.some((existing) => Math.abs(existing.anchorTime - candidate.anchorTime) < tolerance)) {
      continue;
    }
    output.push(candidate);
    if (output.length >= MAX_ROLE_CANDIDATES) break;
  }
  return output;
}

export function generateTransitionCandidates(analysis = {}, role = 'incoming') {
  if (role !== 'incoming' && role !== 'outgoing') return [];
  const window = candidateWindow(analysis, role);
  const boundaries = (Array.isArray(analysis.boundaries) ? analysis.boundaries : [])
    .filter((boundary) => boundary.time >= window.start && boundary.time <= window.end);
  const candidates = boundaries.map((boundary) => candidateFrom(analysis, role, boundary));

  for (const downbeat of analysis.timing?.downbeats || []) {
    if (downbeat < window.start || downbeat > window.end) continue;
    const summary = summaryFor(analysis, role, downbeat);
    const stability = finite(summary.stability) ?? 0.5;
    const vocal = finite(summary.vocal);
    const confidence = clamp(
      (analysis.timing?.downbeatConfidence || 0) * 0.35 +
      stability * 0.35 +
      (vocal === null ? 0.15 : (1 - vocal) * 0.3),
      0,
      0.6
    );
    candidates.push(candidateFrom(analysis, role, {
      time: downbeat,
      confidence,
      source: 'downbeat-evidence',
      evidence: { downbeatDistance: 0 }
    }));
  }

  const beatInterval = finite(analysis.timing?.beatInterval) ?? 0.5;
  return dedupeCandidates(candidates.filter(Boolean), Math.max(0.05, beatInterval / 2), role);
}

function permitsLongTransition(outgoing, incoming, options) {
  const outgoingAnalysis = options.outgoingAnalysis || {};
  const incomingAnalysis = options.incomingAnalysis || {};
  const outgoingVocal = finite(outgoing.summary?.vocal);
  const incomingVocal = finite(incoming.summary?.vocal);
  return outgoing.source === 'detected-change' &&
    incoming.source === 'detected-change' &&
    outgoing.confidence >= 0.65 &&
    incoming.confidence >= 0.65 &&
    (outgoingAnalysis.timing?.beatConfidence || 0) >= 0.7 &&
    (incomingAnalysis.timing?.beatConfidence || 0) >= 0.7 &&
    outgoingVocal !== null && outgoingVocal <= 0.35 &&
    incomingVocal !== null && incomingVocal <= 0.35;
}

function cheapPairScore(outgoing, incoming, beats, beatmatched) {
  const stability = (
    (finite(outgoing.summary?.stability) ?? 0.5) +
    (finite(incoming.summary?.stability) ?? 0.5)
  ) / 2;
  const vocalValues = [outgoing.summary?.vocal, incoming.summary?.vocal]
    .map(finite)
    .filter((value) => value !== null);
  const vocalCleanliness = vocalValues.length
    ? 1 - Math.max(...vocalValues.map((value) => clamp(value)))
    : 0.35;
  const durationPreference = beats === 16 ? 1 : beats === 8 ? 0.85 : beats === 32 ? 0.75 : 0.65;
  return rounded(
    outgoing.confidence * 0.25 +
    incoming.confidence * 0.25 +
    stability * 0.15 +
    vocalCleanliness * 0.15 +
    durationPreference * 0.1 +
    (beatmatched ? 1 : 0.4) * 0.1
  );
}

export function buildCandidatePairs(
  outgoingCandidates = [],
  incomingCandidates = [],
  fit = {},
  options = {}
) {
  const outgoingRange = options.outgoingAnalysis?.audibleRange || { start: 0, end: 0 };
  const incomingRange = options.incomingAnalysis?.audibleRange || { start: 0, end: 0 };
  const targetBpm = finite(fit.targetBpm) ?? 0;
  const outgoingRatio = finite(fit.outgoingRatio) ?? 1;
  const incomingRatio = finite(fit.incomingRatio) ?? 1;
  const pairs = [];
  const rejected = { bounds: 0, tempo: 0 };
  let combinations = 0;
  if (!(targetBpm > 0)) {
    return { finalists: [], diagnostics: { combinations, rejected, detailed: 0 } };
  }

  for (const outgoing of outgoingCandidates.slice(0, MAX_ROLE_CANDIDATES)) {
    for (const incoming of incomingCandidates.slice(0, MAX_ROLE_CANDIDATES)) {
      const beatLengths = permitsLongTransition(outgoing, incoming, options)
        ? [...ORDINARY_BEAT_LENGTHS, 32]
        : ORDINARY_BEAT_LENGTHS;
      for (const beats of beatLengths) {
        combinations += 1;
        const durationSeconds = beats * 60 / targetBpm;
        const outgoingStart = outgoing.anchorTime - durationSeconds * outgoingRatio;
        const incomingStart = incoming.anchorTime - durationSeconds * incomingRatio;
        if (
          outgoingStart < outgoingRange.start - 1e-6 ||
          outgoing.anchorTime > outgoingRange.end + 1e-6 ||
          incomingStart < incomingRange.start - 1e-6 ||
          incoming.anchorTime > incomingRange.end + 1e-6
        ) {
          rejected.bounds += 1;
          continue;
        }
        if (ratioDeviation(outgoingRatio, incomingRatio) > MAX_STRETCH_DEVIATION + 1e-6) {
          rejected.tempo += 1;
          continue;
        }
        const id = `${outgoing.id}>${incoming.id}:${beats}`;
        pairs.push({
          id,
          outgoingCandidate: outgoing,
          incomingCandidate: incoming,
          outgoingStart: rounded(outgoingStart),
          outgoingEnd: outgoing.anchorTime,
          incomingStart: rounded(incomingStart),
          incomingEnd: incoming.anchorTime,
          durationSeconds: rounded(durationSeconds),
          beats,
          targetBpm,
          outgoingRatio,
          incomingRatio,
          beatmatched: Boolean(fit.beatmatched),
          cheapScore: cheapPairScore(outgoing, incoming, beats, Boolean(fit.beatmatched))
        });
      }
    }
  }
  pairs.sort((left, right) => right.cheapScore - left.cheapScore || left.id.localeCompare(right.id));
  const finalists = pairs.slice(0, MAX_DETAILED_CANDIDATES);
  return {
    finalists,
    diagnostics: {
      combinations,
      rejected,
      detailed: finalists.length
    }
  };
}
