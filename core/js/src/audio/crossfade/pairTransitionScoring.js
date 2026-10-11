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

// Measures one candidate overlap and turns the evidence into a classed evaluation.
import {
  interpolateTrackFrame,
  summarizeTrackWindow
} from '../../../shared/trackAnalysis.js';
import { MEASURED_CHANGES, mappedVocalCollision } from './pairTransitionEvidence.js';
import { beatFlow, incomingVoiceRisk, outgoingVoiceRisk } from './transitionFlow.js';
import {
  PAIR_TRANSITION_POLICY,
  candidateGates,
  classFor,
  confidenceFor,
  weightedQuality
} from './pairTransitionGates.js';

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

function sourceStrength(candidate = {}) {
  if (MEASURED_CHANGES.has(candidate.source)) return 1;
  if (candidate.source === 'endpoint') return 0.62;
  if (candidate.source === 'downbeat-evidence') return 0.42;
  return 0.22;
}

function windowSummary(analysis, start, duration, ratio) {
  return summarizeTrackWindow(analysis, start, start + duration * ratio);
}

function spectralSimilarity(left = {}, right = {}) {
  const fields = ['low', 'mid', 'high'];
  let dot = 0;
  let leftPower = 0;
  let rightPower = 0;
  let known = 0;
  for (const field of fields) {
    const a = finite(left[field]);
    const b = finite(right[field]);
    if (a === null || b === null) continue;
    known += 1;
    dot += a * b;
    leftPower += a * a;
    rightPower += b * b;
  }
  if (!known || leftPower <= 0 || rightPower <= 0) return { score: 0.5, coverage: 0 };
  return {
    score: clamp(dot / Math.sqrt(leftPower * rightPower)),
    coverage: known / fields.length
  };
}

function energyScore(pair, outgoing, incoming, outgoingSummary, incomingSummary) {
  const outgoingStart = interpolateTrackFrame(outgoing, 'energy', pair.outgoingStart);
  const outgoingEnd = interpolateTrackFrame(outgoing, 'energy', pair.outgoingEnd);
  const incomingStart = interpolateTrackFrame(incoming, 'energy', pair.incomingStart);
  const incomingEnd = interpolateTrackFrame(incoming, 'energy', pair.incomingEnd);
  const values = [outgoingStart, outgoingEnd, incomingStart, incomingEnd];
  if (values.some((value) => value === null)) {
    const known = [outgoingSummary.energy, incomingSummary.energy]
      .map(finite)
      .filter((value) => value !== null);
    return { score: known.length ? 0.55 : 0.5, coverage: known.length / 2 };
  }
  const outgoingRelease = clamp(0.5 + (outgoingStart - outgoingEnd));
  const incomingArrival = clamp(0.5 + (incomingEnd - incomingStart));
  const levelMatch = 1 - clamp(Math.abs(
    (outgoingStart + outgoingEnd) / 2 - (incomingStart + incomingEnd) / 2
  ));
  return {
    score: clamp(outgoingRelease * 0.35 + incomingArrival * 0.35 + levelMatch * 0.3),
    coverage: 1
  };
}

function vocalScore(collision, outgoingSummary, incomingSummary, voice = {}) {
  const knownMeans = [outgoingSummary.vocal, incomingSummary.vocal]
    .map(finite)
    .filter((value) => value !== null);
  if (collision.coverage <= 0 || !knownMeans.length) {
    return { score: 0.5, coverage: 0 };
  }
  const collisionRisk = Math.max(
    clamp(collision.activeFraction ?? 0),
    clamp((collision.simultaneousMean ?? 0) * 1.15)
  );
  // A vocal-heavy cue is still less desirable than a clean one, but a vocal
  // on only one side is not a collision and must not veto beatmatching. Keep
  // this as a modest ranking cost; mapped simultaneous activity owns the hard
  // safety decision below.
  const soloDensity = Math.max(...knownMeans.map((value) => clamp(value)));
  const soloRisk = clamp((soloDensity - 0.18) / 0.82);
  // A line already in progress when the incoming fades in arrives from nowhere; one
  // cut off as the outgoing leaves is milder because the filter ride covers it.
  const entryRisk = clamp(voice.incoming ?? 0) * 0.45 + clamp(voice.outgoing ?? 0) * 0.15;
  return {
    score: clamp(1 - collisionRisk * 0.72 - soloRisk * 0.24 - entryRisk),
    coverage: collision.coverage
  };
}

function structureScore(pair, outgoing, incoming) {
  const left = pair.outgoingCandidate;
  const right = pair.incomingCandidate;
  const source = Math.sqrt(sourceStrength(left) * sourceStrength(right));
  const confidence = Math.sqrt(clamp(left.confidence) * clamp(right.confidence));
  const meter = Math.sqrt(
    clamp(outgoing.timing?.meter?.confidence) *
    clamp(incoming.timing?.meter?.confidence)
  );
  return clamp(confidence * 0.6 + source * 0.3 + meter * 0.1);
}

function durationScore(beats) {
  if (beats === 16) return 1;
  if (beats === 8) return 0.85;
  if (beats === 32) return 0.75;
  return 0.65;
}

export function evaluatePair(pair, context) {
  const { outgoing, incoming, fit, harmonic } = context;
  const outgoingSummary = windowSummary(
    outgoing,
    pair.outgoingStart,
    pair.durationSeconds,
    pair.outgoingRatio
  );
  const incomingSummary = windowSummary(
    incoming,
    pair.incomingStart,
    pair.durationSeconds,
    pair.incomingRatio
  );
  const collision = mappedVocalCollision(outgoing, incoming, {
    outgoingStart: pair.outgoingStart,
    incomingStart: pair.incomingStart,
    durationSeconds: pair.durationSeconds,
    outgoingRatio: pair.outgoingRatio,
    incomingRatio: pair.incomingRatio,
    tempoRamp: pair.tempoRamp,
    targetBpm: pair.targetBpm
  });
  const voice = {
    incoming: incomingVoiceRisk(pair, incoming),
    outgoing: outgoingVoiceRisk(pair, outgoing)
  };
  const vocal = vocalScore(collision, outgoingSummary, incomingSummary, voice);
  const flow = beatFlow(pair, outgoing, incoming);
  const spectral = spectralSimilarity(outgoingSummary, incomingSummary);
  const gateResult = candidateGates({
    pair,
    fit,
    outgoing,
    incoming,
    harmonic,
    collision,
    vocal,
    spectral: spectral.score,
    contrast: context.contrast ?? null,
    voice
  });
  const energy = energyScore(pair, outgoing, incoming, outgoingSummary, incomingSummary);
  const beatConfidence = Math.sqrt(
    clamp(outgoing.timing?.beatConfidence) * clamp(incoming.timing?.beatConfidence)
  );
  const stabilityValues = [outgoingSummary.stability, incomingSummary.stability]
    .map(finite)
    .filter((value) => value !== null);
  const stability = stabilityValues.length
    ? stabilityValues.reduce((sum, value) => sum + clamp(value), 0) / stabilityValues.length
    : 0.5;
  const components = {
    beat: clamp(beatConfidence * 0.75 + gateResult.phase.score * 0.25),
    structure: structureScore(pair, outgoing, incoming),
    tempo: pair.beatmatched
      ? clamp(1 - fit.deviation / (fit.tempoRamp
        ? PAIR_TRANSITION_POLICY.maxRampDeviation
        : PAIR_TRANSITION_POLICY.maxStretchDeviation))
      : 0.35,
    harmonic: clamp(harmonic.score),
    vocal: vocal.score,
    energy: energy.score,
    spectral: spectral.score,
    stability: clamp(stability),
    duration: durationScore(pair.beats),
    dspRisk: 1,
    flow: flow.score
  };
  for (const name of Object.keys(components)) components[name] = rounded(components[name]);
  const quality = weightedQuality(components, flow.coverage > 0);
  const evidenceCoverage = rounded((
    beatConfidence +
    components.structure +
    harmonic.confidence +
    vocal.coverage +
    energy.coverage +
    spectral.coverage
  ) / 6);
  // A loop is a cheat: it has to sound better than the song itself to be chosen.
  const confidence = rounded(Math.max(0, confidenceFor(quality, evidenceCoverage, gateResult.gates) -
    (pair.loop ? PAIR_TRANSITION_POLICY.loopCost : 0)));
  return {
    id: pair.id,
    pair,
    sources: [pair.outgoingCandidate.source, pair.incomingCandidate.source],
    components,
    harmonic,
    collision,
    flow,
    voice,
    gates: gateResult.gates,
    maximumClass: gateResult.maximumClass,
    phase: gateResult.phase,
    evidenceCoverage,
    quality,
    confidence,
    transitionClass: classFor(confidence, gateResult.maximumClass)
  };
}
