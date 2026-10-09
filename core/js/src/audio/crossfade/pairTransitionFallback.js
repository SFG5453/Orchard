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

// Choreography curves and the boundary or short-fade plans a refusal falls back to.
import {
  CHOREOGRAPHY_STRATEGY,
  CURVE_INTERPOLATION,
  createAutomationPoint,
  createTransitionChoreography
} from './transitionChoreography.js';
import { renderModeFor } from './pairTransitionGates.js';

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

export function buildChoreographyForPlan(pair, evaluation, strategy, native, outgoing, incoming) {
  const duration = pair.durationSeconds;
  const isCut = duration <= 1e-4;
  const isStaged = native && duration >= 1.0;
  const hasBassSwap = strategy === 'bass_swap' || (isStaged && pair.beats >= 8);
  // Locked beats play full-range; the low-pass only masks expected clashes. Snorkels optional.
  const isFiltered = strategy === 'filtered_blend';

  let choreoStrategy = CHOREOGRAPHY_STRATEGY.CLEAN_CUT;
  if (evaluation?.transitionClass === 'silence_trim') {
    choreoStrategy = CHOREOGRAPHY_STRATEGY.SILENCE_TRIM;
  } else if (isStaged) {
    choreoStrategy = CHOREOGRAPHY_STRATEGY.STAGED_BLEND;
  } else if (duration > 0) {
    choreoStrategy = CHOREOGRAPHY_STRATEGY.FILTERED_HANDOFF;
  }

  // 1. Gain curves
  let outgoingGain = [];
  let incomingGain = [];
  if (isCut) {
    outgoingGain = [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)];
    incomingGain = [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)];
  } else if (isStaged) {
    outgoingGain = [
      createAutomationPoint(0.0, 1.0, CURVE_INTERPOLATION.SMOOTH_STEP),
      createAutomationPoint(0.35, 0.95, CURVE_INTERPOLATION.SMOOTH_STEP),
      createAutomationPoint(1.0, 0.0)
    ];
    incomingGain = [
      createAutomationPoint(0.0, 0.0, CURVE_INTERPOLATION.SMOOTH_STEP),
      createAutomationPoint(0.35, 0.38, CURVE_INTERPOLATION.SMOOTH_STEP),
      createAutomationPoint(1.0, 1.0)
    ];
  } else {
    outgoingGain = [
      createAutomationPoint(0.0, 1.0, CURVE_INTERPOLATION.SMOOTH_STEP),
      createAutomationPoint(1.0, 0.0)
    ];
    incomingGain = [
      createAutomationPoint(0.0, 0.0, CURVE_INTERPOLATION.SMOOTH_STEP),
      createAutomationPoint(1.0, 1.0)
    ];
  }

  // 2. Low-pass curve
  let outgoingLowPass = [];
  const maxFreq = 20000;
  const minFreq = isStaged ? 1200 : 700;
  if (isCut) {
    outgoingLowPass = [createAutomationPoint(0.0, maxFreq), createAutomationPoint(1.0, maxFreq)];
  } else if (isFiltered) {
    outgoingLowPass = [
      createAutomationPoint(0.0, maxFreq, CURVE_INTERPOLATION.LOGARITHMIC),
      createAutomationPoint(0.35, maxFreq, CURVE_INTERPOLATION.LOGARITHMIC),
      createAutomationPoint(1.0, minFreq)
    ];
  } else {
    outgoingLowPass = [createAutomationPoint(0.0, maxFreq), createAutomationPoint(1.0, maxFreq)];
  }

  // 3. Bass ownership curves
  let outgoingBass = [];
  let incomingBass = [];
  let bassSwapPoint = null;

  if (isCut) {
    outgoingBass = [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)];
    incomingBass = [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)];
  } else if (hasBassSwap) {
    bassSwapPoint = 0.55;
    const rampHalf = Math.min(0.08, 0.35 / Math.max(1, duration));
    const swapStart = Math.max(0.05, bassSwapPoint - rampHalf);
    const swapEnd = Math.min(0.95, bassSwapPoint + rampHalf);
    outgoingBass = [
      createAutomationPoint(0.0, 1.0),
      createAutomationPoint(swapStart, 1.0, CURVE_INTERPOLATION.EQUAL_POWER_IN),
      createAutomationPoint(swapEnd, 0.0),
      createAutomationPoint(1.0, 0.0)
    ];
    incomingBass = [
      createAutomationPoint(0.0, 0.0),
      createAutomationPoint(swapStart, 0.0, CURVE_INTERPOLATION.EQUAL_POWER_OUT),
      createAutomationPoint(swapEnd, 1.0),
      createAutomationPoint(1.0, 1.0)
    ];
  } else if (duration > 0) {
    bassSwapPoint = 0.5;
    const rampHalf = Math.min(0.08, 0.3 / Math.max(1, duration));
    outgoingBass = [
      createAutomationPoint(0.0, 1.0),
      createAutomationPoint(0.5 - rampHalf, 1.0, CURVE_INTERPOLATION.EQUAL_POWER_IN),
      createAutomationPoint(0.5 + rampHalf, 0.0),
      createAutomationPoint(1.0, 0.0)
    ];
    incomingBass = [
      createAutomationPoint(0.0, 0.0),
      createAutomationPoint(0.5 - rampHalf, 0.0, CURVE_INTERPOLATION.EQUAL_POWER_OUT),
      createAutomationPoint(0.5 + rampHalf, 1.0),
      createAutomationPoint(1.0, 1.0)
    ];
  } else {
    outgoingBass = [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)];
    incomingBass = [createAutomationPoint(0.0, 1.0), createAutomationPoint(1.0, 1.0)];
  }

  const curves = {
    outgoingGain,
    incomingGain,
    outgoingLowPass,
    outgoingBass,
    incomingBass
  };

  return createTransitionChoreography({
    strategy: choreoStrategy,
    outgoing: {
      start: pair.outgoingStart,
      end: pair.outgoingEnd,
      tempoRatio: pair.outgoingRatio
    },
    incoming: {
      cue: pair.incomingStart,
      arrival: pair.incomingEnd,
      resume: pair.incomingEnd,
      tempoRatio: pair.incomingRatio
    },
    duration: pair.durationSeconds,
    dominancePoint: isCut ? 0.5 : 0.55,
    curves,
    bassSwapPoint,
    confidence: evaluation?.confidence ?? 0.5,
    diagnostics: null,
    fallback: null
  });
}

export function fallbackFor(outgoing, incoming, evaluation = null, reason = '') {
  const outgoingRange = outgoing.audibleRange || { start: 0, end: outgoing.duration || 0 };
  const incomingRange = incoming.audibleRange || { start: 0, end: incoming.duration || 0 };
  const selectedClass = evaluation?.transitionClass;

  // These classes explicitly refuse overlap. A silence trim advances at the
  // measured end of audible content; a normal boundary lets the media reach
  // its ordinary end. Keeping them as zero-duration handoffs prevents the
  // live adapter from turning a low-confidence refusal into a crossfade.
  if (selectedClass === 'silence_trim' || selectedClass === 'normal_boundary') {
    const ordinaryEnd = Math.max(outgoingRange.end, finite(outgoing.duration) ?? 0);
    const outgoingEnd = selectedClass === 'silence_trim'
      ? outgoingRange.end
      : ordinaryEnd;
    const pair = {
      outgoingStart: rounded(outgoingEnd),
      outgoingEnd: rounded(outgoingEnd),
      incomingStart: rounded(incomingRange.start),
      incomingEnd: rounded(incomingRange.start),
      durationSeconds: 0,
      outgoingRatio: 1,
      incomingRatio: 1
    };
    const choreo = buildChoreographyForPlan(pair, evaluation, 'boundary_handoff', false, outgoing, incoming);
    return {
      transitionClass: selectedClass,
      outgoingStart: rounded(outgoingEnd),
      outgoingEnd: rounded(outgoingEnd),
      incomingCue: rounded(incomingRange.start),
      durationSeconds: 0,
      strategy: 'boundary_handoff',
      transitionStyle: selectedClass,
      choreography: choreo,
      reason
    };
  }

  const outgoingEnd = evaluation?.pair.outgoingEnd ?? outgoingRange.end;
  const availableOutgoing = Math.max(0, outgoingEnd - outgoingRange.start);
  const availableIncoming = evaluation
    ? Math.max(0, (evaluation.pair.incomingEnd ?? incomingRange.start) - incomingRange.start)
    : Math.max(0, incomingRange.end - incomingRange.start);

  const native = ['full_beatmatched', 'conservative_beatmatched'].includes(selectedClass);
  const evaluatedDuration = evaluation?.pair.durationSeconds ?? 4.0;
  let durationSeconds = native
    ? rounded(Math.min(4.0, Math.max(2.0, evaluatedDuration * 0.35)))
    : rounded(Math.min(4.0, evaluatedDuration));

  durationSeconds = rounded(Math.min(durationSeconds, availableOutgoing, availableIncoming));
  const usable = durationSeconds >= 1.0;
  if (!usable) durationSeconds = 0;

  const incomingArrival = evaluation?.pair.incomingEnd ?? rounded(incomingRange.start + durationSeconds);
  const outgoingStart = rounded(outgoingEnd - durationSeconds);
  const incomingCue = rounded(incomingArrival - durationSeconds);
  const filtered = selectedClass === 'conservative_beatmatched' ||
    evaluation?.gates?.some((g) => ['vocal-collision', 'harmonic-clash', 'spectral-risk'].includes(g.code)) ||
    (evaluation?.collision?.simultaneousMean ?? 0) > 0.25;
  const strategy = usable ? (filtered ? 'filtered_blend' : 'equal_power_crossfade') : 'short_fade';
  const transitionStyle = usable ? (filtered ? 'dj_filter' : 'equal_power') : 'normal';

  const pair = {
    outgoingStart,
    outgoingEnd,
    incomingStart: incomingCue,
    incomingEnd: incomingArrival,
    durationSeconds,
    outgoingRatio: 1,
    incomingRatio: 1
  };
  const choreo = buildChoreographyForPlan(
    pair,
    { ...evaluation, transitionClass: usable ? 'simple_crossfade' : 'normal_boundary' },
    strategy,
    false,
    outgoing,
    incoming
  );

  return {
    transitionClass: usable ? 'simple_crossfade' : 'normal_boundary',
    outgoingStart,
    outgoingEnd,
    incomingCue,
    durationSeconds,
    strategy,
    transitionStyle,
    choreography: choreo,
    reason
  };
}

export function fallbackPlan(outgoing, incoming, generated, reason, confidence = 0) {
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
    choreography: fallback.choreography,
    diagnostics: {
      generated,
      reasonCounts: { [reason]: 1 },
      topCandidates: [],
      selected: null,
      winnerMargin: 0
    }
  };
}
