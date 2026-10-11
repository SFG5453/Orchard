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

import { normalizeTrackAnalysis } from '../../../shared/trackAnalysis.js';
import { createTransitionChoreography } from './transitionChoreography.js';
import {
  buildCandidatePairs,
  generateTransitionCandidates,
  harmonicEvidence,
  tempoFit
} from './pairTransitionEvidence.js';
import {
  PAIR_TRANSITION_POLICY,
  classFor,
  compareEvaluations,
  renderModeFor,
  strategyFor
} from './pairTransitionGates.js';
import { evaluatePair } from './pairTransitionScoring.js';
import { bassSwapFraction, productionContrast } from './transitionFlow.js';
import { findOutgoingLoop, loopedAnalysis } from './outgoingLoop.js';
import {
  buildChoreographyForPlan,
  fallbackFor,
  fallbackPlan
} from './pairTransitionFallback.js';

export { PAIR_TRANSITION_POLICY };

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
    strategy: strategyFor(evaluation),
    confidence: evaluation.confidence,
    quality: evaluation.quality,
    evidenceCoverage: evaluation.evidenceCoverage,
    outgoingTail: evaluation.pair.outgoingCandidate.tail || null,
    components: evaluation.components,
    alignment: evaluation.pair.alignment,
    looped: Boolean(evaluation.pair.loop),
    flow: evaluation.flow,
    voice: evaluation.voice,
    gates: evaluation.gates.map((gate) => gate.code)
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
  nextDuration = 0,
  // Set only by renderers that can glide both tracks' tempo.
  tempoRamp = false,
  // Decoded render windows on each track's timeline; null means the whole track.
  outgoingWindowStart = null,
  incomingWindowEnd = null
} = {}) {
  const outgoing = canonicalAnalysis(analysis, duration);
  const incoming = canonicalAnalysis(nextAnalysis, nextDuration);
  const outgoingCandidates = generateTransitionCandidates(outgoing, 'outgoing', {
    windowStart: outgoingWindowStart
  });
  const incomingCandidates = generateTransitionCandidates(incoming, 'incoming', {
    windowEnd: incomingWindowEnd
  });
  const fit = tempoFit(outgoing.timing?.bpm, incoming.timing?.bpm, {
    tempoRamp: Boolean(tempoRamp)
  });
  const contrast = productionContrast(outgoing, incoming);
  const built = buildCandidatePairs(outgoingCandidates, incomingCandidates, fit, {
    outgoingAnalysis: outgoing,
    incomingAnalysis: incoming,
    outgoingWindowStart,
    incomingWindowEnd
  });
  // A song whose beat stops early may also leave over its last clean bars on repeat.
  const loop = fit.beatmatched ? findOutgoingLoop(outgoing, outgoingWindowStart) : null;
  const looped = loop && loopedAnalysis(outgoing, loop);
  const loopPairs = looped
    ? buildCandidatePairs(
      generateTransitionCandidates(looped, 'outgoing', { windowStart: outgoingWindowStart }),
      incomingCandidates, fit,
      { outgoingAnalysis: looped, incomingAnalysis: incoming, outgoingWindowStart, incomingWindowEnd }
    ).finalists.filter((pair) => pair.outgoingEnd > loop.end)
    : [];
  for (const pair of loopPairs) Object.assign(pair, { id: `${pair.id}:loop`, loop });
  const generated = {
    outgoing: outgoingCandidates.length,
    incoming: incomingCandidates.length,
    combinations: built.diagnostics.combinations,
    detailed: built.diagnostics.detailed,
    rejected: built.diagnostics.rejected,
    looped: loopPairs.length
  };
  if (!built.finalists.length && !loopPairs.length) {
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
  // Loops need the native renderer; a looped blend that only earns a live class is dropped.
  const evaluations = [
    ...built.finalists.map((pair) => evaluatePair(pair, { outgoing, incoming, fit, harmonic, contrast })),
    ...loopPairs
      .map((pair) => evaluatePair(pair, { outgoing: looped, incoming, fit, harmonic, contrast }))
      .filter((evaluation) => renderModeFor(evaluation.transitionClass) === 'native')
  ].sort(compareEvaluations);
  if (!evaluations.length) return fallbackPlan(outgoing, incoming, generated, 'no-credible-candidate');
  const winner = evaluations[0];
  const runnerUp = evaluations.find((evaluation) =>
    evaluation.transitionClass === winner.transitionClass && evaluation.id !== winner.id
  ) || evaluations[1];
  const winnerMargin = rounded(Math.max(0, winner.confidence - (runnerUp?.confidence ?? 0)));
  winner.confidence = rounded(clamp(winner.confidence + Math.min(0.02, winnerMargin * 0.15)));
  winner.transitionClass = classFor(winner.confidence, winner.maximumClass);
  // Songs that cannot be beatmatched play out; a short unmatched fade only sounds like a skip.
  const unmatched = winner.transitionClass === 'simple_crossfade';
  if (unmatched) winner.transitionClass = 'normal_boundary';
  const strategy = strategyFor(winner);
  const fallbackReason = unmatched
    ? (winner.gates.find((gate) => gate.severity === 'veto') || winner.gates[0])?.code || 'confidence-simple'
    : winner.transitionClass === 'silence_trim'
      ? 'confidence-trim'
      : winner.transitionClass === 'normal_boundary'
        ? winner.gates.find((gate) => gate.maximumClass === 'normal_boundary')?.code || 'confidence-boundary'
        : '';
  const fallback = fallbackFor(outgoing, incoming, winner, fallbackReason);
  const native = ['full_beatmatched', 'conservative_beatmatched'].includes(winner.transitionClass);
  const pair = winner.pair;

  const mainChoreo = buildChoreographyForPlan(pair, winner, strategy, native, outgoing, incoming);
  const completeChoreo = createTransitionChoreography({
    ...mainChoreo,
    fallback: fallback.choreography
  });

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
    alignment: pair.alignment,
    // Where the low end changes hands; the renderer cuts the outgoing bass and opens the incoming.
    bassSwapFraction: native ? bassSwapFraction(pair, incoming) : null,
    productionContrast: contrast,
    // Bars the renderer repeats past `outgoingLoop.end`; outgoing times beyond it are loop time.
    ...(pair.loop ? { outgoingLoop: pair.loop } : {}),
    // Glide ratios are overlap averages; the renderer derives each side's curve from them.
    ...(native && pair.tempoRamp ? { tempoRamp: true } : {}),
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
    choreography: completeChoreo,
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
