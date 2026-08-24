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

// This module is deliberately an adapter. PairTransitionPlanner owns every
// musical choice; this file only exposes its exact native-render fields in the
// shape the existing preparation/playback path understands.
import { normalizeTrackAnalysis } from '../../../shared/trackAnalysis.js';
import { planPairTransition } from './pairTransitionPlanner.js';
export { alignTempoOctave } from './transitionPolicy.js';

const SLICE_PADDING_SECONDS = 1.5;
const HANDOFF_FRACTION = 0.5;
const BED_POSITION = 0.5;

function finite(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function canonicalAnalysis(value = {}, duration = 0) {
  if (
    value?.timing &&
    value?.audibleRange &&
    Array.isArray(value?.frames) &&
    Array.isArray(value?.boundaries)
  ) return value;
  return normalizeTrackAnalysis({
    ...value,
    duration: Math.max(finite(value.duration), finite(duration))
  });
}

function sliceFor(start, end, duration) {
  const sliceStart = Math.max(0, start - SLICE_PADDING_SECONDS);
  const sliceEnd = Math.min(duration || Infinity, end + SLICE_PADDING_SECONDS);
  return {
    start: sliceStart,
    end: sliceEnd,
    anchor: start - sliceStart
  };
}

function refusal(pairPlan) {
  return {
    ok: false,
    reason: pairPlan.fallbackReason || pairPlan.transitionClass,
    fallback: pairPlan.fallback,
    pairPlan
  };
}

export function planWsolaTransition({
  analysis = {},
  nextAnalysis = {},
  duration = 0,
  nextDuration = 0
} = {}) {
  const pairPlan = planPairTransition({ analysis, nextAnalysis, duration, nextDuration });
  if (pairPlan.renderMode !== 'native') return refusal(pairPlan);

  const outgoing = canonicalAnalysis(analysis, duration);
  const incoming = canonicalAnalysis(nextAnalysis, nextDuration);
  const outgoingLength = Math.max(finite(duration), finite(outgoing.duration));
  const incomingLength = Math.max(finite(nextDuration), finite(incoming.duration));
  const outgoingGrid = {
    bpm: finite(outgoing.timing?.bpm),
    beats: [...(outgoing.timing?.beats || [])],
    downbeats: [...(outgoing.timing?.downbeats || [])]
  };
  const incomingGrid = {
    bpm: finite(incoming.timing?.bpm),
    beats: [...(incoming.timing?.beats || [])],
    downbeats: [...(incoming.timing?.downbeats || [])]
  };
  const selectedSources = pairPlan.diagnostics?.selected?.sources || [];
  const selectedGates = pairPlan.diagnostics?.selected?.gates || [];

  return {
    ok: true,
    pairPlan,
    fallback: pairPlan.fallback,
    outgoingGrid,
    incomingGrid,
    tier: 'beatmatched',
    beatConfidence: Math.min(
      finite(outgoing.timing?.beatConfidence),
      finite(incoming.timing?.beatConfidence)
    ),
    mixOutType: selectedSources[0] || '',
    vocalClash: selectedGates.includes('vocal-collision'),
    transitionStart: pairPlan.outgoing.start,
    transitionEnd: pairPlan.outgoing.end,
    overlapSeconds: pairPlan.durationSeconds,
    beats: pairPlan.beats,
    fadeBeats: pairPlan.beats,
    handoffFraction: HANDOFF_FRACTION,
    bedPosition: BED_POSITION,
    bassSwapFraction: pairPlan.strategy === 'bass_swap' ? 0.5 : 0.7,
    filterSweep: pairPlan.rendering.filters ? 1 : 0,
    outgoingBpm: outgoingGrid.bpm,
    incomingBpm: incomingGrid.bpm,
    targetBpm: pairPlan.targetBpm,
    outgoingTempoRatio: pairPlan.outgoing.tempoRatio,
    incomingTempoRatio: pairPlan.incoming.tempoRatio,
    stretchRatio: pairPlan.outgoing.tempoRatio,
    incomingCueTime: pairPlan.incoming.start,
    incomingDropTime: pairPlan.incoming.handoff,
    incomingResumeTime: pairPlan.incoming.resume,
    strategy: pairPlan.strategy,
    outgoingSlice: sliceFor(
      pairPlan.outgoing.start,
      pairPlan.outgoing.end,
      outgoingLength
    ),
    incomingSlice: sliceFor(
      pairPlan.incoming.start,
      pairPlan.incoming.resume,
      incomingLength
    )
  };
}
