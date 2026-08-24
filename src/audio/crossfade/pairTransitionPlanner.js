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
  normalizeTrackAnalysis,
  summarizeTrackWindow
} from '../../../shared/trackAnalysis.js';
import {
  MAX_DETAILED_CANDIDATES,
  MAX_ROLE_CANDIDATES,
  buildCandidatePairs,
  generateTransitionCandidates,
  harmonicEvidence,
  mappedVocalCollision,
  tempoFit
} from './pairTransitionEvidence.js';

const CLASS_RANK = Object.freeze({
  normal_boundary: 0,
  silence_trim: 1,
  simple_crossfade: 2,
  conservative_beatmatched: 3,
  full_beatmatched: 4
});

export const PAIR_TRANSITION_POLICY = Object.freeze({
  candidates: Object.freeze({
    perRole: MAX_ROLE_CANDIDATES,
    detailed: MAX_DETAILED_CANDIDATES,
    diagnostics: 5
  }),
  maxStretchDeviation: 0.04,
  minBeatmatchConfidence: 0.55,
  trustedHarmonicConfidence: 0.65,
  vocal: Object.freeze({
    activeThreshold: 0.6,
    activeFraction: 0.3,
    minCoverage: 0.5,
    sustainedBeats: 4,
    denseMean: 0.65
  }),
  confidence: Object.freeze({
    full: 0.78,
    conservative: 0.62,
    simple: 0.42,
    trim: 0.25
  }),
  weights: Object.freeze({
    beat: 0.14,
    structure: 0.14,
    tempo: 0.1,
    harmonic: 0.13,
    vocal: 0.15,
    energy: 0.12,
    spectral: 0.08,
    stability: 0.07,
    duration: 0.04,
    dspRisk: 0.03
  })
});

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

function canonicalAnalysis(value = {}, duration = 0) {
  if (
    value &&
    value.timing &&
    value.audibleRange &&
    value.harmonic &&
    Array.isArray(value.frames) &&
    Array.isArray(value.boundaries)
  ) {
    return value;
  }
  const requestedDuration = Math.max(finite(duration) ?? 0, finite(value.duration) ?? 0);
  return normalizeTrackAnalysis({ ...value, duration: requestedDuration });
}

function lowerCeiling(current, next) {
  return CLASS_RANK[next] < CLASS_RANK[current] ? next : current;
}

function sourceStrength(candidate = {}) {
  if (candidate.source === 'detected-change') return 1;
  if (candidate.source === 'endpoint') return 0.62;
  if (candidate.source === 'downbeat-evidence') return 0.42;
  return 0.22;
}

function nearestDistance(values = [], target = 0) {
  let distance = Infinity;
  for (const value of values) {
    const number = finite(value);
    if (number !== null) distance = Math.min(distance, Math.abs(number - target));
  }
  return distance;
}

function phaseEvidence(pair, outgoing, incoming) {
  const outgoingInterval = finite(outgoing.timing?.beatInterval) ?? 0;
  const incomingInterval = finite(incoming.timing?.beatInterval) ?? 0;
  const outgoingDistance = nearestDistance(
    outgoing.timing?.downbeats,
    pair.outgoingEnd
  );
  const incomingDistance = nearestDistance(
    incoming.timing?.downbeats,
    pair.incomingEnd
  );
  const usable = Number.isFinite(outgoingDistance) && Number.isFinite(incomingDistance);
  const error = usable ? Math.max(outgoingDistance, incomingDistance) : null;
  const tolerance = Math.max(0.08, Math.min(
    outgoingInterval || Infinity,
    incomingInterval || Infinity
  ) * 0.5);
  return {
    beatErrorSeconds: error === null ? null : rounded(error),
    downbeatAligned: error !== null && error <= Math.min(0.08, tolerance),
    score: error === null ? 0.5 : clamp(1 - error / Math.max(tolerance, 0.08))
  };
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

function vocalScore(collision, outgoingSummary, incomingSummary) {
  const outgoingVocal = finite(outgoingSummary.vocal);
  const incomingVocal = finite(incomingSummary.vocal);
  const knownMeans = [outgoingVocal, incomingVocal].filter((value) => value !== null);
  if (collision.coverage <= 0 || !knownMeans.length) {
    return { score: 0.5, coverage: 0, dense: false };
  }
  const collisionRisk = Math.max(
    clamp(collision.activeFraction ?? 0),
    clamp((collision.simultaneousMean ?? 0) * 1.15)
  );
  const soloDensity = Math.max(...knownMeans.map((value) => clamp(value)));
  const soloRisk = clamp((soloDensity - 0.18) / 0.82);
  return {
    score: clamp(1 - collisionRisk * 0.72 - soloRisk * 0.42),
    coverage: collision.coverage,
    dense: soloDensity >= PAIR_TRANSITION_POLICY.vocal.denseMean
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

function addGate(gates, code, maximumClass, severity = 'demotion') {
  gates.push({ code, severity, maximumClass });
}

function candidateGates({ pair, fit, outgoing, incoming, harmonic, collision, vocal }) {
  const gates = [];
  let maximumClass = 'full_beatmatched';
  const lower = (code, next, severity) => {
    addGate(gates, code, next, severity);
    maximumClass = lowerCeiling(maximumClass, next);
  };

  const beatConfidence = Math.min(
    clamp(outgoing.timing?.beatConfidence),
    clamp(incoming.timing?.beatConfidence)
  );
  if (!fit.beatmatched) lower('tempo-distance', 'simple_crossfade', 'veto');
  if (beatConfidence < PAIR_TRANSITION_POLICY.minBeatmatchConfidence) {
    lower('beat-confidence', 'simple_crossfade', 'veto');
  }
  if (harmonic.severeClash) lower('harmonic-clash', 'simple_crossfade', 'veto');

  const sustainedCollision = collision.coverage >= PAIR_TRANSITION_POLICY.vocal.minCoverage &&
    collision.activeFraction >= PAIR_TRANSITION_POLICY.vocal.activeFraction &&
    collision.longestRunBeats >= PAIR_TRANSITION_POLICY.vocal.sustainedBeats;
  if (sustainedCollision) lower('vocal-collision', 'simple_crossfade', 'veto');
  if (vocal.dense && !sustainedCollision) {
    lower('vocal-density', 'simple_crossfade', 'demotion');
  }

  const left = pair.outgoingCandidate;
  const right = pair.incomingCandidate;
  const detected = [left, right].filter((candidate) => candidate.source === 'detected-change').length;
  const structureConfidence = Math.min(clamp(left.confidence), clamp(right.confidence));
  const meterConfidence = Math.min(
    clamp(outgoing.timing?.meter?.confidence),
    clamp(incoming.timing?.meter?.confidence)
  );
  if (detected < 2 || structureConfidence < 0.55 || meterConfidence < 0.35) {
    const ceiling = detected === 0 ? 'simple_crossfade' : 'conservative_beatmatched';
    lower('structure-confidence', ceiling, 'demotion');
  }

  if (
    harmonic.confidence < 0.25 ||
    collision.coverage < PAIR_TRANSITION_POLICY.vocal.minCoverage
  ) {
    lower('evidence-coverage', 'conservative_beatmatched', 'demotion');
  }

  const phase = phaseEvidence(pair, outgoing, incoming);
  const interval = Math.min(
    finite(outgoing.timing?.beatInterval) ?? Infinity,
    finite(incoming.timing?.beatInterval) ?? Infinity
  );
  if (phase.beatErrorSeconds !== null && phase.beatErrorSeconds > Math.max(0.2, interval * 0.45)) {
    lower('phase-error', 'simple_crossfade', 'veto');
  }
  return { gates, maximumClass, phase };
}

function weightedQuality(components) {
  return rounded(Object.entries(PAIR_TRANSITION_POLICY.weights).reduce(
    (sum, [name, weight]) => sum + components[name] * weight,
    0
  ));
}

function confidenceFor(quality, evidenceCoverage, gates) {
  const vetoes = gates.filter((gate) => gate.severity === 'veto').length;
  const demotions = gates.length - vetoes;
  return clamp(
    quality * 0.82 + evidenceCoverage * 0.18 - vetoes * 0.06 - demotions * 0.015
  );
}

function classFor(confidence, maximumClass) {
  const maximumRank = CLASS_RANK[maximumClass];
  if (
    maximumRank >= CLASS_RANK.full_beatmatched &&
    confidence >= PAIR_TRANSITION_POLICY.confidence.full
  ) return 'full_beatmatched';
  if (
    maximumRank >= CLASS_RANK.conservative_beatmatched &&
    confidence >= PAIR_TRANSITION_POLICY.confidence.conservative
  ) return 'conservative_beatmatched';
  if (confidence >= PAIR_TRANSITION_POLICY.confidence.simple) return 'simple_crossfade';
  if (confidence >= PAIR_TRANSITION_POLICY.confidence.trim) return 'silence_trim';
  return 'normal_boundary';
}

function evaluatePair(pair, context) {
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
    targetBpm: pair.targetBpm
  });
  const vocal = vocalScore(collision, outgoingSummary, incomingSummary);
  const gateResult = candidateGates({
    pair,
    fit,
    outgoing,
    incoming,
    harmonic,
    collision,
    vocal
  });
  const energy = energyScore(pair, outgoing, incoming, outgoingSummary, incomingSummary);
  const spectral = spectralSimilarity(outgoingSummary, incomingSummary);
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
    tempo: fit.beatmatched
      ? clamp(1 - fit.deviation / PAIR_TRANSITION_POLICY.maxStretchDeviation)
      : 0.35,
    harmonic: clamp(harmonic.score),
    vocal: vocal.score,
    energy: energy.score,
    spectral: spectral.score,
    stability: clamp(stability),
    duration: durationScore(pair.beats),
    dspRisk: 1
  };
  for (const name of Object.keys(components)) components[name] = rounded(components[name]);
  const quality = weightedQuality(components);
  const evidenceCoverage = rounded((
    beatConfidence +
    components.structure +
    harmonic.confidence +
    vocal.coverage +
    energy.coverage +
    spectral.coverage
  ) / 6);
  const confidence = rounded(confidenceFor(quality, evidenceCoverage, gateResult.gates));
  return {
    id: pair.id,
    pair,
    sources: [pair.outgoingCandidate.source, pair.incomingCandidate.source],
    components,
    harmonic,
    collision,
    gates: gateResult.gates,
    maximumClass: gateResult.maximumClass,
    phase: gateResult.phase,
    evidenceCoverage,
    quality,
    confidence,
    transitionClass: classFor(confidence, gateResult.maximumClass)
  };
}

function compareEvaluations(left, right) {
  return CLASS_RANK[right.transitionClass] - CLASS_RANK[left.transitionClass] ||
    right.confidence - left.confidence ||
    right.quality - left.quality ||
    left.id.localeCompare(right.id);
}

function strategyFor(evaluation) {
  if (evaluation.transitionClass === 'simple_crossfade') return 'equal_power_crossfade';
  if (evaluation.transitionClass === 'silence_trim') return 'short_fade';
  if (evaluation.transitionClass === 'normal_boundary') return 'short_fade';
  const outgoingLow = finite(evaluation.pair.outgoingCandidate.summary?.low);
  const incomingLow = finite(evaluation.pair.incomingCandidate.summary?.low);
  if (outgoingLow !== null && incomingLow !== null && outgoingLow >= 0.62 && incomingLow >= 0.62) {
    return 'bass_swap';
  }
  if (
    evaluation.components.harmonic < 0.55 ||
    evaluation.components.spectral < 0.55 ||
    (evaluation.collision.simultaneousMean ?? 0) > 0.25
  ) return 'filtered_blend';
  return 'beatmatched_crossfade';
}

function renderModeFor(transitionClass) {
  if (['full_beatmatched', 'conservative_beatmatched'].includes(transitionClass)) return 'native';
  if (transitionClass === 'simple_crossfade') return 'live';
  return 'boundary';
}

function fallbackFor(outgoing, incoming, evaluation = null, reason = '') {
  const outgoingRange = outgoing.audibleRange || { start: 0, end: outgoing.duration || 0 };
  const incomingRange = incoming.audibleRange || { start: 0, end: incoming.duration || 0 };
  const outgoingEnd = evaluation?.pair.outgoingEnd ?? outgoingRange.end;
  const incomingCue = evaluation?.pair.incomingStart ?? incomingRange.start;
  const availableOutgoing = Math.max(0, outgoingEnd - outgoingRange.start);
  const availableIncoming = Math.max(0, incomingRange.end - incomingCue);
  const durationSeconds = rounded(Math.min(8, availableOutgoing, availableIncoming));
  const usable = durationSeconds >= 1;
  return {
    transitionClass: usable ? 'simple_crossfade' : 'normal_boundary',
    outgoingStart: rounded(Math.max(outgoingRange.start, outgoingEnd - durationSeconds)),
    outgoingEnd: rounded(outgoingEnd),
    incomingCue: rounded(incomingCue),
    durationSeconds,
    strategy: usable ? 'equal_power_crossfade' : 'short_fade',
    transitionStyle: usable ? 'equal_power' : 'normal',
    reason
  };
}

function reasonCounts(evaluations) {
  const counts = {};
  for (const evaluation of evaluations) {
    for (const gate of evaluation.gates) counts[gate.code] = (counts[gate.code] || 0) + 1;
  }
  return Object.fromEntries(Object.entries(counts).sort(([left], [right]) => left.localeCompare(right)));
}

function diagnosticCandidate(evaluation) {
  return {
    id: evaluation.id,
    outgoingEnd: evaluation.pair.outgoingEnd,
    incomingHandoff: evaluation.pair.incomingEnd,
    beats: evaluation.pair.beats,
    sources: evaluation.sources,
    transitionClass: evaluation.transitionClass,
    confidence: evaluation.confidence,
    quality: evaluation.quality,
    evidenceCoverage: evaluation.evidenceCoverage,
    components: evaluation.components,
    gates: evaluation.gates.map((gate) => gate.code)
  };
}

function fallbackPlan(outgoing, incoming, generated, reason, confidence = 0) {
  const fallback = fallbackFor(outgoing, incoming, null, reason);
  return {
    status: 'fallback',
    transitionClass: fallback.transitionClass,
    renderMode: renderModeFor(fallback.transitionClass),
    outgoing: {
      start: fallback.outgoingStart,
      end: fallback.outgoingEnd,
      tempoRatio: 1
    },
    incoming: {
      start: fallback.incomingCue,
      handoff: fallback.incomingCue,
      resume: fallback.incomingCue,
      tempoRatio: 1
    },
    durationSeconds: fallback.durationSeconds,
    beats: 0,
    targetBpm: 0,
    phase: { beatErrorSeconds: null, downbeatAligned: false },
    strategy: fallback.strategy,
    rendering: {
      fade: fallback.transitionStyle,
      bassHandoff: false,
      filters: false,
      vocalDuckCurve: []
    },
    confidence: rounded(confidence),
    fallbackReason: reason,
    fallback,
    diagnostics: {
      generated,
      reasonCounts: { [reason]: 1 },
      topCandidates: [],
      selected: null,
      winnerMargin: 0
    }
  };
}

/**
 * Authoritatively chooses one desktop transition for a track pair. All
 * musical decisions happen here; renderers may validate or refuse this plan,
 * but they must not move its cues or select another strategy.
 */
export function planPairTransition({
  analysis = {},
  nextAnalysis = {},
  duration = 0,
  nextDuration = 0
} = {}) {
  const outgoing = canonicalAnalysis(analysis, duration);
  const incoming = canonicalAnalysis(nextAnalysis, nextDuration);
  const outgoingCandidates = generateTransitionCandidates(outgoing, 'outgoing');
  const incomingCandidates = generateTransitionCandidates(incoming, 'incoming');
  const fit = tempoFit(outgoing.timing?.bpm, incoming.timing?.bpm);
  const built = buildCandidatePairs(outgoingCandidates, incomingCandidates, fit, {
    outgoingAnalysis: outgoing,
    incomingAnalysis: incoming
  });
  const generated = {
    outgoing: outgoingCandidates.length,
    incoming: incomingCandidates.length,
    combinations: built.diagnostics.combinations,
    detailed: built.diagnostics.detailed,
    rejected: built.diagnostics.rejected
  };
  if (!built.finalists.length) {
    const outgoingBpm = finite(outgoing.timing?.bpm) ?? 0;
    const incomingBpm = finite(incoming.timing?.bpm) ?? 0;
    const reason = !(outgoingBpm > 0)
      ? 'outgoing-tempo'
      : !(incomingBpm > 0)
        ? 'incoming-tempo'
        : !incomingCandidates.some((candidate) => candidate.runwaySeconds > 0)
          ? 'incoming-runway'
          : 'no-credible-candidate';
    return fallbackPlan(outgoing, incoming, generated, reason);
  }

  const harmonic = harmonicEvidence(outgoing, incoming);
  const evaluations = built.finalists
    .map((pair) => evaluatePair(pair, { outgoing, incoming, fit, harmonic }))
    .sort(compareEvaluations);
  const winner = evaluations[0];
  const runnerUp = evaluations.find((evaluation) =>
    evaluation.transitionClass === winner.transitionClass && evaluation.id !== winner.id
  ) || evaluations[1];
  const winnerMargin = rounded(Math.max(0, winner.confidence - (runnerUp?.confidence ?? 0)));
  winner.confidence = rounded(clamp(winner.confidence + Math.min(0.02, winnerMargin * 0.15)));
  winner.transitionClass = classFor(winner.confidence, winner.maximumClass);
  const strategy = strategyFor(winner);
  const fallbackReason = winner.transitionClass === 'simple_crossfade'
    ? winner.gates[0]?.code || 'confidence-simple'
    : winner.transitionClass === 'silence_trim'
      ? 'confidence-trim'
      : winner.transitionClass === 'normal_boundary'
        ? 'confidence-boundary'
        : '';
  const fallback = fallbackFor(outgoing, incoming, winner, fallbackReason);
  const native = ['full_beatmatched', 'conservative_beatmatched'].includes(winner.transitionClass);
  const pair = winner.pair;

  return {
    status: native ? 'planned' : 'fallback',
    transitionClass: winner.transitionClass,
    renderMode: renderModeFor(winner.transitionClass),
    outgoing: {
      start: pair.outgoingStart,
      end: pair.outgoingEnd,
      tempoRatio: pair.outgoingRatio
    },
    incoming: {
      start: pair.incomingStart,
      handoff: pair.incomingEnd,
      resume: pair.incomingEnd,
      tempoRatio: pair.incomingRatio
    },
    durationSeconds: pair.durationSeconds,
    beats: pair.beats,
    targetBpm: pair.targetBpm,
    phase: {
      beatErrorSeconds: winner.phase.beatErrorSeconds,
      downbeatAligned: winner.phase.downbeatAligned
    },
    strategy,
    rendering: {
      fade: native ? 'equal_power' : fallback.transitionStyle,
      bassHandoff: strategy === 'bass_swap',
      filters: ['filtered_blend', 'bass_swap'].includes(strategy),
      vocalDuckCurve: []
    },
    confidence: winner.confidence,
    fallbackReason,
    fallback,
    diagnostics: {
      generated,
      reasonCounts: reasonCounts(evaluations),
      topCandidates: evaluations
        .slice(0, PAIR_TRANSITION_POLICY.candidates.diagnostics)
        .map(diagnosticCandidate),
      selected: diagnosticCandidate(winner),
      winnerMargin
    }
  };
}
