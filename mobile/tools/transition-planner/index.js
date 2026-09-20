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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Android executes the desktop sources; this adapter only caches pair decisions between ticks.
import { planTransition, transitionFromPairFallback } from '../../../src/audio/crossfade/transitionPlanner.js';
import { planWsolaTransition } from '../../../src/audio/crossfade/wsolaPlanner.js';

const cache = new Map();
const MAX_CACHE_ENTRIES = 2;
export function invoke(method, json) {
  const input = JSON.parse(json);
  input.currentTrack ||= {};
  input.nextTrack ||= {};
  const currentTime = Math.max(0, Number(input.currentTime) || 0);
  delete input.currentTime;
  const key = method + JSON.stringify(input);
  let plan = cache.get(key);
  if (!plan) {
    plan = method === 'native' ? planWsolaTransition(input) : planTransition(input);
    // One live and one native result cover the active playback pair. Retaining sixteen complete
    // analyses and planner graphs consumed nearly the entire Android Java heap after Best Mix had
    // visited a queue, even though no caller could use those old pair decisions again.
    if (cache.size >= MAX_CACHE_ENTRIES) cache.delete(cache.keys().next().value);
    cache.set(key, plan);
  }
  if (method === 'native') return JSON.stringify(plan);
  if (plan.pairPlan) {
    return JSON.stringify(transitionFromPairFallback(
      plan.pairPlan, input.analysis, input.nextAnalysis,
      Math.max(input.duration || 0, input.currentTrack?.durationSeconds || 0),
      currentTime, input.minFadeSeconds ?? 1, input.fadeSeconds ?? 6
    ));
  }
  if (!plan.markerVisible) return JSON.stringify(plan);
  const shouldStart = currentTime >= plan.transitionStart;
  const reason = shouldStart
    ? plan.reason === 'before-gapless-window' ? 'same-album-gapless'
      : plan.reason.replace(/^before-/, '').replace(/-window$/, '')
    : plan.reason;
  return JSON.stringify({ ...plan, shouldStart, reason });
}
