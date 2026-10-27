/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

import { normalizeTrackAnalysis } from '../../../shared/trackAnalysis.js';
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
import { bassSwapFraction, chooseBassSwap, productionContrast } from './transitionFlow.js';
import { findOutgoingLoop, loopedAnalysis } from './outgoingLoop.js';
import { fallbackFor, fallbackPlan } from './pairTransitionFallback.js';
import { TECHNIQUE, createArrangement, fadeSpansFor } from './transitionArrangement.js';
import { explainRefusal } from './transitionExplain.js';
import { planBeatlessBridge } from './beatlessBridge.js';
import { planMuffles } from './muffle.js';

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

/** The best of ranked evaluations, with its confidence nudged by its margin over the next. */
function settle(evaluations) {
  const winner = evaluations[0];
  const runnerUp = evaluations.find((evaluation) =>
    evaluation.transitionClass === winner.transitionClass && evaluation.id !== winner.id
  ) || evaluations[1];
  const winnerMargin = rounded(Math.max(0, winner.confidence - (runnerUp?.confidence ?? 0)));
  winner.confidence = rounded(clamp(winner.confidence + Math.min(0.02, winnerMargin * 0.15)));
  winner.transitionClass = classFor(winner.confidence, winner.maximumClass);
  // Songs that cannot be beatmatched play out; a short unmatched fade only sounds like a skip.
  if (winner.transitionClass === 'simple_crossfade') winner.transitionClass = 'normal_boundary';
  return { winner, winnerMargin };
}

function bridgePlan(outgoing, incoming, bridge, generated, beatmatch, contrast) {
  const { pair } = bridge;
  return {
    status: 'planned',
    transitionClass: 'beatless_bridge',
    renderMode: renderModeFor('beatless_bridge'),
    outgoing: { start: pair.outgoingStart, end: pair.outgoingEnd, tempoRatio: 1 },
    incoming: { start: pair.incomingStart, handoff: pair.incomingEnd, resume: pair.incomingEnd, tempoRatio: 1 },
    durationSeconds: pair.durationSeconds,
    beats: pair.beats,
    targetBpm: pair.targetBpm,
    alignment: 'end',
    bassSwapFraction: null,
    productionContrast: contrast,
    phase: { beatErrorSeconds: null, downbeatAligned: false },
    strategy: 'equal_power_crossfade',
    technique: TECHNIQUE.BRIDGE,
    arrangement: createArrangement({ technique: TECHNIQUE.BRIDGE }),
    rendering: { fade: 'equal_power', bassHandoff: false, filters: false, vocalDuckCurve: [] },
    confidence: bridge.confidence,
    fallbackReason: '',
    explain: null,
    fallback: fallbackFor(outgoing, incoming, null, ''),
    diagnostics: {
      generated,
      reasonCounts: {},
      topCandidates: [],
      selected: null,
      winnerMargin: 0,
      // Why no beatmatched mix was possible, and what the bridge measured instead.
      beatmatch,
      bridge: { skip: bridge.skip, harmony: bridge.harmony, collision: bridge.collision, voice: bridge.voice }
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
  // The new song's opening bars, where a drop-in may start it.
  const openingEnd = (incoming.audibleRange?.start ?? 0) + 8 * (finite(incoming.timing?.beatInterval) ?? 0.5);
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
  // Drop-ins only enter on a song's opening bars: the immediate intro no blend can sit on.
  const dropIn = () => {
    const candidates = incomingCandidates.filter((candidate) => candidate.anchorTime <= openingEnd);
    if (!candidates.length) return [];
    return buildCandidatePairs(outgoingCandidates, candidates, fit, {
      outgoingAnalysis: outgoing, incomingAnalysis: incoming, outgoingWindowStart, incomingWindowEnd, dropIn: true
    }).finalists.map((pair) => evaluatePair(pair, { outgoing, incoming, fit, harmonic: harmonicEvidence(outgoing, incoming), contrast }))
      .filter((evaluation) => renderModeFor(classFor(evaluation.confidence, evaluation.maximumClass)) === 'native')
      .sort(compareEvaluations);
  };
  const generated = {
    outgoing: outgoingCandidates.length,
    incoming: incomingCandidates.length,
    combinations: built.diagnostics.combinations,
    detailed: built.diagnostics.detailed,
    rejected: built.diagnostics.rejected,
    looped: loopPairs.length
  };
  // Without a beatmatched mix, beatless edges can still bridge at any tempo.
  const refuse = (explain, options = {}) => {
    const bridge = planBeatlessBridge(outgoing, incoming, { outgoingWindowStart, incomingWindowEnd, contrast });
    if (bridge.ok) return bridgePlan(outgoing, incoming, bridge, generated, explain, contrast);
    const explained = { ...explain, bridge: { code: bridge.code, detail: bridge.detail } };
    return {
      ...fallbackPlan(outgoing, incoming, generated, explain.code, explained, options),
      productionContrast: contrast
    };
  };
  if (!built.finalists.length && !loopPairs.length) {
    const outgoingBpm = finite(outgoing.timing?.bpm) ?? 0;
    const incomingBpm = finite(incoming.timing?.bpm) ?? 0;
    const precheck = !(outgoingBpm > 0)
      ? 'outgoing-tempo'
      : !(incomingBpm > 0)
        ? 'incoming-tempo'
        : !incomingCandidates.some((candidate) => candidate.runwaySeconds > 0)
          ? 'incoming-runway'
          : '';
    return refuse(explainRefusal({
      fit, fits: built.diagnostics.fits, nearest: built.diagnostics.nearest,
      rejected: built.diagnostics.rejected, precheck
    }));
  }

  const harmonic = harmonicEvidence(outgoing, incoming);
  // Loops need the native renderer; a looped blend that only earns a live class is dropped.
  const evaluations = [
    ...built.finalists.map((pair) => evaluatePair(pair, { outgoing, incoming, fit, harmonic, contrast })),
    ...loopPairs
      .map((pair) => evaluatePair(pair, { outgoing: looped, incoming, fit, harmonic, contrast }))
      .filter((evaluation) => renderModeFor(evaluation.transitionClass) === 'native')
  ].sort(compareEvaluations);
  if (!evaluations.length) {
    return refuse(explainRefusal({
      fit, fits: built.diagnostics.fits, nearest: built.diagnostics.nearest, rejected: built.diagnostics.rejected
    }));
  }
  let { winner, winnerMargin } = settle(evaluations);
  let native = ['full_beatmatched', 'conservative_beatmatched'].includes(winner.transitionClass);
  const drops = native ? [] : dropIn();
  if (drops.length) ({ winner, winnerMargin } = settle(drops));
  native ||= drops.length > 0;
  // A pair that only needed a filter can still leave behind a muffle.
  const muffles = native ? [] : planMuffles(evaluations, { outgoing, incoming, fit, harmonic, contrast });
  if (muffles.length) ({ winner, winnerMargin } = settle(muffles));
  native ||= muffles.length > 0;
  const strategy = strategyFor(winner);
  const explain = native ? null : explainRefusal({
    matched: evaluations.filter((evaluation) => evaluation.pair.beatmatched),
    fit, fits: built.diagnostics.fits, nearest: built.diagnostics.nearest,
    rejected: built.diagnostics.rejected, contrast
  });
  const diagnostics = {
    generated,
    reasonCounts: reasonCounts(evaluations),
    topCandidates: evaluations
      .slice(0, PAIR_TRANSITION_POLICY.candidates.diagnostics)
      .map(diagnosticCandidate),
    selected: diagnosticCandidate(winner),
    winnerMargin
  };
  if (!native) return refuse(explain, { evaluation: winner, confidence: winner.confidence, diagnostics });
  const fallback = fallbackFor(outgoing, incoming, winner, '');
  const pair = winner.pair;
  // A bass swap's bar line follows the music; a muffle keeps the one its gates were judged on.
  const swap = strategy === 'bass_swap' && !pair.exitStyle
    ? chooseBassSwap(pair, pair.loop ? looped : outgoing, incoming)
    : bassSwapFraction(pair, incoming);
  const spans = fadeSpansFor(pair);
  const arrangement = createArrangement({
    technique: pair.alignment === 'start'
      ? TECHNIQUE.DROP_IN
      : pair.exitStyle ? pair.exitStyle
        : strategy === 'bass_swap' ? TECHNIQUE.BASS_SWAP : TECHNIQUE.BLEND,
    outgoingFade: spans.outgoing,
    incomingFade: spans.incoming,
    bassSwapFraction: swap
  });

  return {
    status: 'planned',
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
    bassSwapFraction: swap,
    productionContrast: contrast,
    // Bars the renderer repeats past `outgoingLoop.end`; outgoing times beyond it are loop time.
    ...(pair.loop ? { outgoingLoop: pair.loop } : {}),
    // Glide ratios are overlap averages; the renderer derives each side's curve from them.
    ...(pair.tempoRamp ? { tempoRamp: true } : {}),
    phase: {
      beatErrorSeconds: winner.phase.beatErrorSeconds,
      downbeatAligned: winner.phase.downbeatAligned
    },
    strategy,
    technique: arrangement.technique,
    arrangement,
    rendering: {
      fade: 'equal_power',
      bassHandoff: ['bass_swap', 'muffle_swap'].includes(strategy),
      filters: ['filtered_blend', 'bass_swap', 'muffle_swap'].includes(strategy),
      vocalDuckCurve: []
    },
    confidence: winner.confidence,
    fallbackReason: '',
    explain: null,
    fallback,
    diagnostics
  };
}
