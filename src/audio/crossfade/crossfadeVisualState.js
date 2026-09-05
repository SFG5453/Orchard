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

import { evaluateAutomationCurve } from './transitionChoreography.js';

export function clampMixProgress(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(1, number));
}

export function equalPowerMixWeights(progress) {
  const position = clampMixProgress(progress);
  return {
    outgoingGain: Math.cos(position * Math.PI * 0.5),
    incomingGain: Math.sin(position * Math.PI * 0.5)
  };
}

/**
 * Returns the gain pair represented by a rendered Smart Crossfade buffer.
 * Native WSOLA playback contains both tracks in one PCM source, so the live
 * graph cannot be metered per deck. Its choreography curves are the exact
 * envelopes used by the renderer and are therefore the authoritative visual
 * input; equal-power is the safe fallback for older rendered plans.
 */
export function renderedMixWeights(progress, choreography = null) {
  const position = clampMixProgress(progress);
  const outgoing = choreography?.curves?.outgoingGain;
  const incoming = choreography?.curves?.incomingGain;
  if (!Array.isArray(outgoing) || !outgoing.length || !Array.isArray(incoming) || !incoming.length) {
    return equalPowerMixWeights(position);
  }

  return {
    outgoingGain: clampMixProgress(evaluateAutomationCurve(outgoing, position)),
    incomingGain: clampMixProgress(evaluateAutomationCurve(incoming, position))
  };
}

export function normalizedIncomingWeight(outgoingGain, incomingGain, fallbackProgress = 0) {
  const outgoing = clampMixProgress(outgoingGain);
  const incoming = clampMixProgress(incomingGain);
  const total = outgoing + incoming;
  return total > 0.0001 ? incoming / total : clampMixProgress(fallbackProgress);
}

export function smartCrossfadePhase({
  complete = false,
  handoffProgress = 0.5,
  progress = 0,
  started = true
} = {}) {
  if (complete) return 'complete';
  if (!started) return 'preparing';
  const position = clampMixProgress(progress);
  if (position <= 0.06) return 'mix-start';
  if (position >= clampMixProgress(handoffProgress)) return 'handoff';
  return 'active-mix';
}
