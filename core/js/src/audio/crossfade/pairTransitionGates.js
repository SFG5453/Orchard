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

// Class ceilings, gates and strategy policy shared by every pair evaluation.
import {
  MAX_DETAILED_CANDIDATES,
  MAX_RAMP_DEVIATION,
  MAX_ROLE_CANDIDATES,
  MEASURED_CHANGES
} from './pairTransitionEvidence.js';
import { STYLE_CLASH_CONTRAST, STYLE_RISK_CONTRAST, halfBars } from './transitionFlow.js';

export const CLASS_RANK = Object.freeze({
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
  maxRampDeviation: MAX_RAMP_DEVIATION,
  minBeatmatchConfidence: 0.55,
  // Confidence a looped outgoing gives up against playing the song as recorded.
  loopCost: 0.01,
  trustedHarmonicConfidence: 0.65,
  vocal: Object.freeze({
    activeThreshold: 0.6,
    activeFraction: 0.3,
    minCoverage: 0.5,
    sustainedBeats: 4
  }),
  confidence: Object.freeze({
    full: 0.78,
    conservative: 0.62,
    simple: 0.42,
    trim: 0.25
  }),
  // Beat flow takes weight from the coarse frame measures it supersedes.
  weights: Object.freeze({
    beat: 0.14,
    structure: 0.1,
    tempo: 0.1,
    harmonic: 0.13,
    vocal: 0.15,
    energy: 0.06,
    spectral: 0.05,
    stability: 0.04,
    duration: 0.02,
    dspRisk: 0.03,
    flow: 0.18
  }),
  // Analyses without bass curves (Best Mix summaries, cached analyses) use these weights.
  legacyWeights: Object.freeze({
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

function lowerCeiling(current, next) {
  return CLASS_RANK[next] < CLASS_RANK[current] ? next : current;
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
  // A measured drop may sit half a bar off a double-time reading's bar lines.
  const incomingDistance = nearestDistance(
    pair.incomingCandidate?.source === 'beat-entry'
      ? halfBars(incoming.timing?.downbeats, incomingInterval)
      : incoming.timing?.downbeats,
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

export function addGate(gates, code, maximumClass, severity = 'demotion') {
  gates.push({ code, severity, maximumClass });
}

/**
 * Overlaps that need the low-pass to hide a clash: keys apart, unlike spectra or two
 * voices. Weak key reads score a neutral 0.5, so only trusted keys may call a clash.
 */
export function needsFilter(harmonic, spectral, collision) {
  const trustedHarmonic = harmonic.confidence >= PAIR_TRANSITION_POLICY.trustedHarmonicConfidence;
  return (trustedHarmonic && clamp(harmonic.score) < 0.55) ||
    spectral < 0.55 ||
    (collision.simultaneousMean ?? 0) > 0.25;
}

export function candidateGates({
  pair, fit, outgoing, incoming, harmonic, collision, vocal, spectral = 1, contrast = null, voice = {}
}) {
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
  // Clashing productions keep the natural boundary: the old song ends, the new one starts.
  if (contrast !== null && (contrast >= STYLE_CLASH_CONTRAST ||
    (contrast >= STYLE_RISK_CONTRAST && needsFilter(harmonic, spectral, collision)))) {
    lower('style-contrast', 'normal_boundary', 'veto');
  }
  if (!fit.beatmatched) lower('tempo-distance', 'simple_crossfade', 'veto');
  if (beatConfidence < PAIR_TRANSITION_POLICY.minBeatmatchConfidence) {
    lower('beat-confidence', 'simple_crossfade', 'veto');
  }
  if (harmonic.severeClash) lower('harmonic-clash', 'simple_crossfade', 'veto');

  const sustainedCollision = collision.coverage >= PAIR_TRANSITION_POLICY.vocal.minCoverage &&
    collision.activeFraction >= PAIR_TRANSITION_POLICY.vocal.activeFraction &&
    collision.longestRunBeats >= PAIR_TRANSITION_POLICY.vocal.sustainedBeats;
  // Analysis "vocal" values are broad spectral-risk estimates, so synth-heavy
  // passages can look vocal. Keep a safe beatmatch eligible and let this risk
  // cap confidence and select the filtered native blend instead of vetoing it.
  if (sustainedCollision) {
    lower('vocal-collision', 'conservative_beatmatched', 'demotion');
  }
  // Measured vocals only: a sustained two-voice overlap, or one that enters and leaves
  // mid-line, swaps one singer for another, so the songs play out instead.
  const measuredVoice = Number.isFinite(voice.incoming) && Number.isFinite(voice.outgoing);
  if (measuredVoice && (sustainedCollision || Math.min(voice.incoming, voice.outgoing) >= 0.5)) {
    lower('voice-over-voice', 'normal_boundary', 'veto');
  }
  // Fading the outgoing singer out mid-line sounds forced even over a clean incoming.
  if (measuredVoice && voice.outgoing >= 0.3) lower('voice-cut', 'normal_boundary', 'veto');

  const left = pair.outgoingCandidate;
  const right = pair.incomingCandidate;
  const detected = [left, right].filter((candidate) => MEASURED_CHANGES.has(candidate.source)).length;
  const structureConfidence = Math.min(clamp(left.confidence), clamp(right.confidence));
  const meterConfidence = Math.min(
    clamp(outgoing.timing?.meter?.confidence),
    clamp(incoming.timing?.meter?.confidence)
  );
  if (detected < 2 || structureConfidence < 0.55 || meterConfidence < 0.35) {
    const usesRhythmicFallback = [left, right]
      .some((candidate) => candidate.source === 'rhythmic-fallback');
    const ceiling = usesRhythmicFallback ? 'simple_crossfade' : 'conservative_beatmatched';
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

export function weightedQuality(components, measuredFlow = false) {
  const weights = measuredFlow ? PAIR_TRANSITION_POLICY.weights : PAIR_TRANSITION_POLICY.legacyWeights;
  return rounded(Object.entries(weights).reduce(
    (sum, [name, weight]) => sum + components[name] * weight,
    0
  ));
}

export function confidenceFor(quality, evidenceCoverage, gates) {
  const vetoes = gates.filter((gate) => gate.severity === 'veto').length;
  const demotions = gates.length - vetoes;
  return clamp(
    quality * 0.82 + evidenceCoverage * 0.18 - vetoes * 0.06 - demotions * 0.015
  );
}

export function classFor(confidence, maximumClass) {
  const thresholds = PAIR_TRANSITION_POLICY.confidence;
  const earned = confidence >= thresholds.full
    ? 'full_beatmatched'
    : confidence >= thresholds.conservative
      ? 'conservative_beatmatched'
      : confidence >= thresholds.simple
        ? 'simple_crossfade'
        : confidence >= thresholds.trim ? 'silence_trim' : 'normal_boundary';
  return lowerCeiling(earned, maximumClass);
}

export function compareEvaluations(left, right) {
  return CLASS_RANK[right.transitionClass] - CLASS_RANK[left.transitionClass] ||
    right.confidence - left.confidence ||
    right.quality - left.quality ||
    left.id.localeCompare(right.id);
}

export function strategyFor(evaluation) {
  if (evaluation.transitionClass === 'silence_trim') return 'boundary_handoff';
  if (evaluation.transitionClass === 'normal_boundary') return 'boundary_handoff';
  if (evaluation.transitionClass === 'simple_crossfade') {
    if (
      (evaluation.collision.longestRunBeats ?? 0) >= 4 ||
      (evaluation.collision.activeFraction ?? 0) >= 0.3 ||
      (evaluation.collision.simultaneousMean ?? 0) > 0.25
    ) {
      return evaluation.pair.durationSeconds <= 0 ? 'boundary_handoff' : 'filtered_blend';
    }
    return 'filtered_blend';
  }
  const measured = (evaluation.flow?.coverage ?? 0) > 0;
  if (needsFilter(evaluation.harmonic, evaluation.components.spectral, evaluation.collision)) {
    return 'filtered_blend';
  }
  // The filtered blend also swaps bass, so a clash wins; two beats under each other
  // otherwise need one bassline at a time.
  const outgoingLow = finite(evaluation.pair.outgoingCandidate.summary?.low);
  const incomingLow = finite(evaluation.pair.incomingCandidate.summary?.low);
  const bothBass = measured
    ? evaluation.flow.sharedBeat >= 0.25
    : outgoingLow !== null && incomingLow !== null && outgoingLow >= 0.62 && incomingLow >= 0.62;
  return bothBass ? 'bass_swap' : 'beatmatched_crossfade';
}

export function renderModeFor(transitionClass) {
  if (['full_beatmatched', 'conservative_beatmatched'].includes(transitionClass)) return 'native';
  if (transitionClass === 'simple_crossfade') return 'live';
  return 'boundary';
}
