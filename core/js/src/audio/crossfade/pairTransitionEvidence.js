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
import {
  MAX_RAMP_DEVIATION,
  MAX_STRETCH_DEVIATION,
  ratioDeviation
} from './pairTempoHarmony.js';

export {
  MAX_RAMP_DEVIATION,
  MAX_STRETCH_DEVIATION,
  glideOffsets,
  harmonicEvidence,
  tempoFit
} from './pairTempoHarmony.js';
import { glideOffsets } from './pairTempoHarmony.js';
import { bassArrival, beatEntry, beatFlow, incomingVoiceRisk } from './transitionFlow.js';

export const MAX_ROLE_CANDIDATES = 12;
export const MAX_DETAILED_CANDIDATES = 64;
export const MAX_DISCARDED_AUDIBLE_SECONDS = 12;
export const VOCAL_ACTIVE_THRESHOLD = 0.6;
export const ORDINARY_BEAT_LENGTHS = Object.freeze([4, 8, 16]);
const MIN_BEATMATCH_BEATS = 8;
const MIN_BEATMATCH_SECONDS = 4;
const AUDIBLE_ENERGY_FRACTION = 0.1;
// Seconds kept between a handoff and the end of the decoded incoming window.
const WINDOW_MARGIN = 0.25;

// Anchors backed by a measured change in the music.
export const MEASURED_CHANGES = new Set(['detected-change', 'beat-entry']);

const SOURCE_PRIORITY = new Map([
  ['detected-change', 4],
  ['beat-entry', 4],
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
    const offsets = mapping.tempoRamp
      ? glideOffsets(time, duration, outgoingRatio, incomingRatio)
      : { outgoing: time * outgoingRatio, incoming: time * incomingRatio };
    const outgoingVocal = interpolateTrackFrame(
      outgoing,
      'vocal',
      outgoingStart + offsets.outgoing
    );
    const incomingVocal = interpolateTrackFrame(
      incoming,
      'vocal',
      incomingStart + offsets.incoming
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

/**
 * Measures how much audible source material remains after an outgoing anchor.
 * Unknown intervals are charged as audible: absence of frame evidence is not
 * evidence of silence, and cutting a track short is the unsafe direction in
 * which to guess.
 */
export function audibleTailEvidence(analysis = {}, anchorTime = 0) {
  const range = analysis.audibleRange || { start: 0, end: analysis.duration || 0 };
  const start = Math.max(range.start, Math.min(range.end, finite(anchorTime) ?? range.end));
  const end = Math.max(start, finite(range.end) ?? start);
  const spanSeconds = Math.max(0, end - start);
  if (spanSeconds <= 1e-6) {
    return {
      spanSeconds: 0,
      audibleSeconds: 0,
      unknownSeconds: 0,
      chargedAudibleSeconds: 0,
      coverage: 1,
      classification: 'endpoint'
    };
  }

  const frames = (Array.isArray(analysis.frames) ? analysis.frames : [])
    .map((frame) => ({ time: finite(frame?.time), energy: finite(frame?.energy) }))
    .filter((frame) => frame.time !== null)
    .sort((left, right) => left.time - right.time);
  const knownEnergies = frames
    .map((frame) => frame.energy)
    .filter((value) => value !== null && value >= 0)
    .sort((left, right) => left - right);
  const reference = knownEnergies.length
    ? knownEnergies[Math.floor((knownEnergies.length - 1) * 0.85)]
    : null;
  const threshold = reference !== null && reference > 0
    ? reference * AUDIBLE_ENERGY_FRACTION
    : Infinity;
  let knownSeconds = 0;
  let audibleSeconds = 0;
  for (let index = 0; index + 1 < frames.length; index += 1) {
    const left = frames[index];
    const right = frames[index + 1];
    const segmentStart = Math.max(start, left.time);
    const segmentEnd = Math.min(end, right.time);
    if (!(segmentEnd > segmentStart) || left.energy === null || right.energy === null) continue;
    const seconds = segmentEnd - segmentStart;
    knownSeconds += seconds;
    if ((left.energy + right.energy) / 2 >= threshold) audibleSeconds += seconds;
  }
  knownSeconds = Math.min(spanSeconds, knownSeconds);
  const unknownSeconds = Math.max(0, spanSeconds - knownSeconds);
  const chargedAudibleSeconds = Math.min(spanSeconds, audibleSeconds + unknownSeconds);
  const coverage = spanSeconds > 0 ? knownSeconds / spanSeconds : 1;
  const classification = coverage < 0.5
    ? 'unknown'
    : audibleSeconds <= 0.5 && unknownSeconds <= 0.5
      ? 'silence'
      : chargedAudibleSeconds <= MAX_DISCARDED_AUDIBLE_SECONDS
        ? 'short-tail'
        : 'continuing';
  return {
    spanSeconds: rounded(spanSeconds),
    audibleSeconds: rounded(audibleSeconds),
    unknownSeconds: rounded(unknownSeconds),
    chargedAudibleSeconds: rounded(chargedAudibleSeconds),
    coverage: rounded(coverage),
    classification
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
  const tailCost = candidate.tail
    ? clamp(candidate.tail.chargedAudibleSeconds / MAX_DISCARDED_AUDIBLE_SECONDS)
    : 0;
  return source * 0.2 + candidate.confidence * 0.55 + stability * 0.15 + clean * 0.1 - tailCost * 0.15;
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
    ...(role === 'outgoing' ? { tail: audibleTailEvidence(analysis, anchorTime) } : {}),
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

/**
 * Mix-out or mix-in anchors for one track. `limits` clips them to the decoded render
 * windows: `windowStart` for the outgoing tail, `windowEnd` for the incoming head.
 */
export function generateTransitionCandidates(analysis = {}, role = 'incoming', limits = {}) {
  if (role !== 'incoming' && role !== 'outgoing') return [];
  const window = candidateWindow(analysis, role);
  const windowStart = finite(limits.windowStart);
  const windowEnd = finite(limits.windowEnd);
  if (role === 'outgoing' && windowStart !== null) window.start = Math.max(window.start, windowStart);
  if (role === 'incoming' && windowEnd !== null) window.end = Math.min(window.end, windowEnd);
  const boundaries = (Array.isArray(analysis.boundaries) ? analysis.boundaries : [])
    .filter((boundary) => boundary.time >= window.start && boundary.time <= window.end);
  const candidates = boundaries.map((boundary) => candidateFrom(analysis, role, boundary));
  const entry = role === 'incoming' ? beatEntry(analysis, window) : null;
  if (entry) candidates.push(candidateFrom(analysis, role, entry));
  const arrival = role === 'incoming' ? bassArrival(analysis, window) : null;
  if (arrival) candidates.push(candidateFrom(analysis, role, arrival));

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
  const eligible = candidates.filter((candidate) => candidate && (
    role !== 'outgoing' ||
    candidate.tail.chargedAudibleSeconds <= MAX_DISCARDED_AUDIBLE_SECONDS
  ));
  return dedupeCandidates(eligible, Math.max(0.05, beatInterval / 2), role);
}

function permitsLongTransition(outgoing, incoming, options) {
  const outgoingAnalysis = options.outgoingAnalysis || {};
  const incomingAnalysis = options.incomingAnalysis || {};
  const outgoingVocal = finite(outgoing.summary?.vocal);
  const incomingVocal = finite(incoming.summary?.vocal);
  return MEASURED_CHANGES.has(outgoing.source) &&
    MEASURED_CHANGES.has(incoming.source) &&
    outgoing.confidence >= 0.65 &&
    incoming.confidence >= 0.65 &&
    (outgoingAnalysis.timing?.beatConfidence || 0) >= 0.7 &&
    (incomingAnalysis.timing?.beatConfidence || 0) >= 0.7 &&
    outgoingVocal !== null && outgoingVocal <= 0.35 &&
    incomingVocal !== null && incomingVocal <= 0.35;
}

function durationSpecsFor(outgoing, incoming, fit, matched, options) {
  const targetBpm = finite(fit.targetBpm) ?? 0;
  if (matched) {
    return (permitsLongTransition(outgoing, incoming, options) ? [...ORDINARY_BEAT_LENGTHS, 32] : ORDINARY_BEAT_LENGTHS)
      .map((beats) => ({ beats, durationSeconds: beats * 60 / targetBpm }));
  }
  return [
    { beats: 0, durationSeconds: 2.0 },
    { beats: 0, durationSeconds: 3.0 },
    { beats: 0, durationSeconds: 4.0 },
    ...(4 * 60 / targetBpm <= 4.0 ? [{ beats: 4, durationSeconds: 4 * 60 / targetBpm }] : [])
  ];
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
  const outgoingAnalysis = options.outgoingAnalysis || {};
  const incomingAnalysis = options.incomingAnalysis || {};
  const outgoingRange = outgoingAnalysis.audibleRange || { start: 0, end: 0 };
  const incomingRange = incomingAnalysis.audibleRange || { start: 0, end: 0 };
  // Overlaps must fit the decoded render windows when the caller names them.
  const earliestOut = Math.max(outgoingRange.start, finite(options.outgoingWindowStart) ?? -Infinity);
  const latestIn = Math.min(incomingRange.end, (finite(options.incomingWindowEnd) ?? Infinity) - WINDOW_MARGIN);
  const targetBpm = finite(fit.targetBpm) ?? 0;
  const matched = Boolean(fit.beatmatched);
  // Unmatched fits keep native ratios and plan ordinary short blends.
  const outgoingRatio = matched ? finite(fit.outgoingRatio) ?? 1 : 1;
  const incomingRatio = matched ? finite(fit.incomingRatio) ?? 1 : 1;
  const tempoRamp = Boolean(fit.tempoRamp && matched);
  const tempoDeviation = tempoRamp
    ? finite(fit.deviation) ?? Infinity
    : ratioDeviation(outgoingRatio, incomingRatio);
  const tempoLimit = tempoRamp ? MAX_RAMP_DEVIATION : MAX_STRETCH_DEVIATION;
  const pairs = [];
  const rejected = { bounds: 0, tempo: 0, duration: 0 };
  let combinations = 0;
  if (!(targetBpm > 0)) {
    return { finalists: [], diagnostics: { combinations, rejected, detailed: 0 } };
  }

  for (const outgoing of outgoingCandidates.slice(0, MAX_ROLE_CANDIDATES)) {
    for (const incoming of incomingCandidates.slice(0, MAX_ROLE_CANDIDATES)) {
      for (const spec of durationSpecsFor(outgoing, incoming, fit, matched, options)) {
        const beats = spec.beats;
        const durationSeconds = spec.durationSeconds;
        if (matched && (beats < MIN_BEATMATCH_BEATS || durationSeconds < MIN_BEATMATCH_SECONDS - 1e-6)) {
          combinations += 1;
          rejected.duration += 1;
          continue;
        }
        if (!matched && durationSeconds > 4.0 + 1e-6) {
          combinations += 1;
          rejected.duration += 1;
          continue;
        }
        // "end" lands the incoming anchor as the old song leaves; "mid" drops it on the
        // bass swap so a beatless intro never fills the whole overlap.
        const alignments = matched && beats % 8 === 0 ? ['end', 'mid'] : ['end'];
        for (const alignment of alignments) {
          combinations += 1;
          const outgoingStart = outgoing.anchorTime - durationSeconds * outgoingRatio;
          const lead = durationSeconds * incomingRatio * (alignment === 'mid' ? 0.5 : 1);
          const incomingStart = incoming.anchorTime - lead;
          const incomingEnd = incomingStart + durationSeconds * incomingRatio;
          if (
            outgoingStart < earliestOut - 1e-6 ||
            outgoing.anchorTime > outgoingRange.end + 1e-6 ||
            incomingStart < incomingRange.start - 1e-6 ||
            incomingEnd > latestIn + 1e-6 ||
            // Glide renderers replay the incoming track for the overlap's length before its handoff.
            (tempoRamp && incomingEnd - durationSeconds < -1e-6)
          ) {
            rejected.bounds += 1;
            continue;
          }
          if (tempoDeviation > tempoLimit + 1e-6) {
            rejected.tempo += 1;
            continue;
          }
          const length = beats > 0 ? beats : durationSeconds.toFixed(1);
          const pair = {
            id: `${outgoing.id}>${incoming.id}:${length}s${alignment === 'mid' ? ':mid' : ''}`,
            outgoingCandidate: outgoing,
            incomingCandidate: incoming,
            outgoingStart: rounded(outgoingStart),
            outgoingEnd: outgoing.anchorTime,
            incomingStart: rounded(incomingStart),
            incomingEnd: rounded(incomingEnd),
            incomingAnchor: incoming.anchorTime,
            alignment,
            durationSeconds: rounded(durationSeconds),
            beats,
            targetBpm,
            outgoingRatio,
            incomingRatio,
            ...(tempoRamp ? { tempoRamp } : {}),
            beatmatched: matched
          };
          const flow = beatFlow(pair, outgoingAnalysis, incomingAnalysis);
          const voice = incomingVoiceRisk(pair, incomingAnalysis) ?? 0;
          pair.cheapScore = rounded(cheapPairScore(outgoing, incoming, beats, matched) +
            (flow.coverage > 0 ? flow.score * 0.25 : 0) - voice * 0.2);
          pairs.push(pair);
        }
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
