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

import { ref, watch } from 'vue';
import { finalizeTrackAnalysis } from '../../../shared/trackAnalysis.js';
import { planPairTransition } from '../../audio/crossfade/pairTransitionPlanner.js';
import { ANALYSIS_PRIORITIES } from '../../audio/crossfade/smartCrossfadeAnalysis.js';
import { loadLearnedAudioProfiles } from '../../audio/engine/audioProfileStore.js';
import { fetchBatchCloudAnalysis } from '../../services/cloudAnalysisSync.js';

const BEST_MIX_TRACK_LIMIT = 50;
const BEST_MIX_PLANNER_SHORTLIST = 3;

function persistCloudAnalysis(trackId, analysis) {
  try {
    const pending = globalThis.orchardAudioAnalysis?.store?.(trackId, analysis);
    void Promise.resolve(pending).catch(() => {});
  } catch {
    // Cloud evidence remains usable for this sort even when the optional local
    // bridge is unavailable or refuses a cache write.
  }
}

const KEY_INDEX = new Map([
  ['C', 0], ['C♯', 1], ['D♭', 1], ['D', 2], ['D♯', 3], ['E♭', 3],
  ['E', 4], ['F', 5], ['F♯', 6], ['G♭', 6], ['G', 7], ['G♯', 8],
  ['A♭', 8], ['A', 9], ['A♯', 10], ['B♭', 10], ['B', 11]
]);

function normalizedTempoRatio(leftBpm, rightBpm) {
  const left = Number(leftBpm) || 0;
  const right = Number(rightBpm) || 0;
  if (!left || !right) return 0;
  let ratio = right / left;
  while (ratio > 1.5) ratio /= 2;
  while (ratio < 0.67) ratio *= 2;
  return ratio;
}

function parsedKey(value = '') {
  const [root, mode] = String(value).split(' ');
  const index = KEY_INDEX.get(root);
  return Number.isInteger(index) ? { index, mode } : null;
}

function harmonicCost(left = '', right = '') {
  const [leftRoot, leftMode] = String(left).split(' ');
  const [rightRoot, rightMode] = String(right).split(' ');
  const leftIndex = KEY_INDEX.get(leftRoot);
  const rightIndex = KEY_INDEX.get(rightRoot);
  if (!Number.isInteger(leftIndex) || !Number.isInteger(rightIndex)) return null;
  if (leftMode !== rightMode) {
    const relative = (leftMode === 'major' && rightIndex === (leftIndex + 9) % 12) ||
      (rightMode === 'major' && leftIndex === (rightIndex + 9) % 12);
    if (relative) return 0.05;
    if (leftIndex === rightIndex) return 0.22;
    const pitchDistance = Math.min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12);
    return Math.min(1, 0.35 + pitchDistance / 10);
  }
  const circleDistance = Math.min(((leftIndex * 7) % 12 - (rightIndex * 7) % 12 + 12) % 12, ((rightIndex * 7) % 12 - (leftIndex * 7) % 12 + 12) % 12);
  if (circleDistance === 0) return 0;
  if (circleDistance === 1) return 0.12;
  if (circleDistance === 2) return 0.38;
  return Math.min(1, 0.55 + circleDistance * 0.09);
}

function clamp01(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, Math.min(1, number)) : 0;
}

function confidence(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.max(0.15, Math.min(1, number)) : fallback;
}

function finiteOrNull(value, minimum = -Infinity) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) && number > minimum ? number : null;
}

function trackDurationSeconds(track = {}) {
  const direct = Number(track.durationSeconds) || 0;
  if (direct > 0) return direct;
  const parts = String(track.duration || '').trim().split(':').map(Number);
  if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
  return parts.reduce((total, part) => total * 60 + part, 0);
}

function edgeEnergy(curve, fromEnd) {
  if (!Array.isArray(curve) || !curve.length) return null;
  const count = Math.min(6, Math.max(2, Math.ceil(curve.length * 0.08)));
  const points = fromEnd ? curve.slice(-count) : curve.slice(0, count);
  const energies = points.map((point) => Number(point?.energy)).filter(Number.isFinite);
  return energies.length ? energies.reduce((sum, value) => sum + value, 0) / energies.length : null;
}

export function hasMusicalAnalysis(analysis = {}) {
  return Number(analysis.bpm) > 0 || Boolean(parsedKey(analysis.key));
}

function legacyTransitionCost(left = {}, right = {}) {
  let weightedCost = 0;
  let totalWeight = 0;
  const tempoRatio = normalizedTempoRatio(left.bpm, right.bpm);
  if (tempoRatio) {
    const weight = 4 * Math.sqrt(confidence(left.tempoConfidence ?? left.beatConfidence, 0.35) * confidence(right.tempoConfidence ?? right.beatConfidence, 0.35));
    weightedCost += Math.min(1.5, Math.abs(Math.log2(tempoRatio)) / Math.log2(1.2)) * weight;
    totalWeight += weight;
  }

  const keyCost = harmonicCost(left.key, right.key);
  if (keyCost !== null) {
    const weight = 2.4 * Math.sqrt(confidence(left.keyConfidence, 0.35) * confidence(right.keyConfidence, 0.35));
    weightedCost += keyCost * weight;
    totalWeight += weight;
  }

  const leftLoudness = finiteOrNull(left.loudnessLufs, -69);
  const rightLoudness = finiteOrNull(right.loudnessLufs, -69);
  if (leftLoudness !== null && rightLoudness !== null) {
    const weight = 0.55;
    weightedCost += Math.min(1, Math.abs(leftLoudness - rightLoudness) / 12) * weight;
    totalWeight += weight;
  }

  const outgoingEnergy = edgeEnergy(left.energyCurve, true);
  const incomingEnergy = edgeEnergy(right.energyCurve, false);
  if (outgoingEnergy !== null && incomingEnergy !== null) {
    const weight = 0.45;
    weightedCost += Math.min(1, Math.abs(outgoingEnergy - incomingEnergy) / 1.5) * weight;
    totalWeight += weight;
  }

  const leftVocal = finiteOrNull(left.vocalProbability, -0.001);
  const rightVocal = finiteOrNull(right.vocalProbability, -0.001);
  if (leftVocal !== null && rightVocal !== null) {
    const weight = 0.35;
    weightedCost += clamp01((leftVocal - 0.5) * 2) * clamp01((rightVocal - 0.5) * 2) * weight;
    totalWeight += weight;
  }

  return totalWeight > 0 ? weightedCost / totalWeight : null;
}

const TRANSITION_CLASS_COST = Object.freeze({
  full_beatmatched: 0,
  conservative_beatmatched: 1,
  simple_crossfade: 2,
  silence_trim: 3,
  normal_boundary: 4
});

export function transitionCost(left = {}, right = {}) {
  const legacyCost = legacyTransitionCost(left, right);
  if (legacyCost === null) return null;
  const plan = planPairTransition({
    analysis: left,
    nextAnalysis: right,
    duration: Number(left.duration) || 0,
    nextDuration: Number(right.duration) || 0
  });
  const classCost = TRANSITION_CLASS_COST[plan.transitionClass] ?? TRANSITION_CLASS_COST.normal_boundary;
  const confidenceCost = 1 - clamp01(plan.confidence);
  const qualityCost = 1 - clamp01(plan.diagnostics?.selected?.quality ?? plan.confidence);
  return classCost * 10 + confidenceCost * 2 + qualityCost + legacyCost * 0.01;
}

function orderAnalyzedSegment(segment, initialAnalysis) {
  const remaining = [...segment];
  const ordered = [];
  let comparisons = 0;
  let plannerEvaluations = 0;
  let previous = initialAnalysis;
  if (!hasMusicalAnalysis(previous) && remaining.length) {
    const [first] = remaining.splice(0, 1);
    ordered.push(first);
    previous = first.analysis;
  }

  while (remaining.length) {
    const comparable = remaining
      .map((candidate, index) => ({
        candidate,
        index,
        prefilterCost: legacyTransitionCost(previous, candidate.analysis)
      }))
      .filter((entry) => entry.prefilterCost !== null);
    let bestIndex = 0;
    if (comparable.length) {
      comparisons += 1;
      comparable.sort((left, right) =>
        left.prefilterCost - right.prefilterCost ||
        left.candidate.originalIndex - right.candidate.originalIndex
      );
      const finalists = comparable.slice(0, BEST_MIX_PLANNER_SHORTLIST)
        .map((entry) => ({
          ...entry,
          cost: transitionCost(previous, entry.candidate.analysis)
        }));
      plannerEvaluations += finalists.length;
      finalists.sort((left, right) =>
        left.cost - right.cost ||
        left.prefilterCost - right.prefilterCost ||
        left.candidate.originalIndex - right.candidate.originalIndex
      );
      bestIndex = finalists[0].index;
    }
    const [selected] = remaining.splice(bestIndex, 1);
    ordered.push(selected);
    previous = selected.analysis;
  }
  return { comparisons, ordered, plannerEvaluations };
}

export function bestTransitionOrder(queue, analysisByTrack, initialAnalysis = {}) {
  const output = [];
  let comparisons = 0;
  let plannerEvaluations = 0;
  let segment = [];
  let previous = initialAnalysis;

  function flushSegment() {
    if (!segment.length) return;
    const result = orderAnalyzedSegment(segment, previous);
    output.push(...result.ordered.map((entry) => entry.track));
    comparisons += result.comparisons;
    plannerEvaluations += result.plannerEvaluations;
    previous = result.ordered.at(-1)?.analysis || previous;
    segment = [];
  }

  queue.forEach((track, originalIndex) => {
    const analysis = analysisByTrack.get(track.id) || {};
    if (hasMusicalAnalysis(analysis)) {
      segment.push({ analysis, originalIndex, track });
      return;
    }
    flushSegment();
    output.push(track);
    previous = {};
  });
  flushSegment();
  return { comparisons, ordered: output, plannerEvaluations };
}

function isOrderedSubset(currentIds, expectedIds) {
  let cursor = 0;
  for (const id of currentIds) {
    while (cursor < expectedIds.length && expectedIds[cursor] !== id) cursor += 1;
    if (cursor >= expectedIds.length) return false;
    cursor += 1;
  }
  return true;
}

function isOrderedQueueWithAppendedTracks(currentIds, expectedIds) {
  const expected = new Set(expectedIds);
  const retained = [];
  let sawAddition = false;
  for (const id of currentIds) {
    if (!expected.has(id)) {
      sawAddition = true;
      continue;
    }
    // Refill and Autoplay append. An expected item after a new item means the
    // queue was manually inserted/reordered instead of naturally extended.
    if (sawAddition) return false;
    retained.push(id);
  }
  return sawAddition && retained.length > 0 && isOrderedSubset(retained, expectedIds);
}

export function installQueueTransitionSort(ctx) {
  ctx.transitionQueueSorted = ref(false);
  ctx.transitionQueueSortBusy = ref(false);
  ctx.transitionQueueSortSnapshot = [];
  ctx.transitionQueueExpectedIds = [];
  ctx.transitionQueueSortAnalyzedCount = ref(0);
  ctx.transitionQueueSortTotalCount = ref(0);
  let learnedTempoPromise = null;
  let refreshRequested = false;

  function analysisFor(track, learnedTempo, cached = {}, bpmMetadata = {}) {
    const smart = ctx.crossfadeAnalysisByTrack?.get(track?.id) || {};
    const activeAnalysis = track?.id === ctx.activeTrack.value?.id && ctx.crossfadeAnalysis.value?.status === 'ready'
      ? ctx.crossfadeAnalysis.value : {};
    const sources = [smart, activeAnalysis, cached, bpmMetadata, track || {}];
    const tempoSource = sources.find((source) => Number(source?.bpm || source?.tempo) > 0);
    const keySource = sources.find((source) => parsedKey(source?.key));
    const loudnessSource = sources.find((source) => finiteOrNull(source?.loudnessLufs, -69) !== null);
    const energySource = sources.find((source) => Array.isArray(source?.energyCurve) && source.energyCurve.length);
    const vocalSource = sources.find((source) => finiteOrNull(source?.vocalProbability, -0.001) !== null);
    const merged = Object.assign({}, ...[...sources].reverse());
    return finalizeTrackAnalysis({
      ...merged,
      duration: Number(merged.duration) || trackDurationSeconds(track),
      bpm: Number(tempoSource?.bpm || tempoSource?.tempo || learnedTempo) || 0,
      tempoConfidence: Number(tempoSource?.tempoConfidence ?? tempoSource?.beatConfidence) || (tempoSource ? 0.35 : (learnedTempo ? 0.25 : 0)),
      beatConfidence: Number(tempoSource?.beatConfidence) || 0,
      key: keySource?.key || '',
      keyConfidence: Number(keySource?.keyConfidence) || (keySource ? 0.35 : 0),
      loudnessLufs: loudnessSource?.loudnessLufs ?? null,
      energyCurve: energySource?.energyCurve || [],
      vocalProbability: vocalSource?.vocalProbability ?? null
    });
  }

  async function learnedTempoMap() {
    learnedTempoPromise ||= loadLearnedAudioProfiles()
      .then((profiles) => new Map(profiles.map((profile) => [profile.trackId, profile.tempo])))
      .catch(() => new Map());
    return learnedTempoPromise;
  }

  async function cachedAnalysisMap(tracks) {
    const getCached = globalThis.orchardAudioAnalysis?.get;
    const unique = Array.from(new Map(tracks.filter((t) => t?.id).map((t) => [t.id, t])).values());
    ctx.transitionQueueSortTotalCount.value = unique.length;
    ctx.transitionQueueSortAnalyzedCount.value = 0;
    const map = new Map();
    const missing = [];

    await Promise.all(unique.map(async (track) => {
      const local = getCached ? await getCached(track.id).catch(() => null) : null;
      if (local) {
        map.set(track.id, local);
        ctx.transitionQueueSortAnalyzedCount.value++;
      } else {
        missing.push(track);
      }
    }));

    if (missing.length > 0) {
      const cloudResults = await fetchBatchCloudAnalysis(missing.map((t) => t.id)).catch(() => new Map());
      for (const [id, cloudData] of cloudResults.entries()) {
        map.set(id, cloudData);
        ctx.transitionQueueSortAnalyzedCount.value++;
        persistCloudAnalysis(id, cloudData);
      }
    }
    return map;
  }

  function analysisStream(track) {
    if (track?.id === ctx.activeTrack.value?.id && track.streamUrl) return track.streamUrl;
    const prepared = ctx.nextTrackPreload?.value;
    if (prepared?.track?.id === track?.id && prepared.resolved?.streamUrl) return prepared.resolved.streamUrl;
    return async () => {
      const resolved = await ctx.resolvePlayableTrack(track, { mediaKind: 'audio', preload: true });
      return resolved?.streamUrl || '';
    };
  }

  async function localAnalysisMap(tracks) {
    const analyze = ctx.smartCrossfadeAnalyzer?.analyze;
    if (typeof analyze !== 'function' || typeof ctx.resolvePlayableTrack !== 'function') {
      return cachedAnalysisMap(tracks);
    }
    const unique = Array.from(new Map(tracks.filter((t) => t?.id).map((t) => [t.id, t])).values());
    ctx.transitionQueueSortTotalCount.value = unique.length;
    ctx.transitionQueueSortAnalyzedCount.value = 0;

    const resultMap = new Map();
    const needed = [];
    const getCached = globalThis.orchardAudioAnalysis?.get;

    await Promise.all(unique.map(async (track) => {
      const local = getCached ? await getCached(track.id).catch(() => null) : null;
      if (local) {
        resultMap.set(track.id, local);
        ctx.transitionQueueSortAnalyzedCount.value++;
      } else {
        needed.push(track);
      }
    }));

    if (needed.length > 0) {
      const cloudResults = await fetchBatchCloudAnalysis(needed.map((t) => t.id)).catch(() => new Map());
      const stillNeeded = [];
      for (const track of needed) {
        if (cloudResults.has(track.id)) {
          const cloudData = cloudResults.get(track.id);
          resultMap.set(track.id, cloudData);
          ctx.transitionQueueSortAnalyzedCount.value++;
          persistCloudAnalysis(track.id, cloudData);
        } else {
          stillNeeded.push(track);
        }
      }

      const activeId = ctx.activeTrack.value?.id;
      const nextId = ctx.queue.value[0]?.id;
      await Promise.all(stillNeeded.map(async (track) => {
        const priority = track.id === activeId
          ? ANALYSIS_PRIORITIES.current
          : track.id === nextId ? ANALYSIS_PRIORITIES.next : ANALYSIS_PRIORITIES.background;
        try {
          const analysis = await analyze.call(
            ctx.smartCrossfadeAnalyzer,
            track.id,
            analysisStream(track),
            { duration: trackDurationSeconds(track), priority }
          );
          ctx.transitionQueueSortAnalyzedCount.value++;
          if (analysis) resultMap.set(track.id, analysis);
        } catch (error) {
          ctx.transitionQueueSortAnalyzedCount.value++;
          ctx.smartCrossfadeAnalyzer.report?.('background-track-failed', {
            trackId: track.id,
            errorName: String(error?.name || 'Error'),
            errorMessage: String(error?.message || error || 'Unknown error')
          });
        }
      }));
    }
    return resultMap;
  }

  function applyQueueOrder(queue) {
    ctx.transitionQueueExpectedIds = queue.map((track) => track.id);
    ctx.queue.value = queue;
    if (ctx.shuffleEnabled.value) ctx.shuffleSourceQueue.value = [...queue];
    ctx.clearNextPreload();
    void ctx.preloadNextTrack();
  }

  ctx.restoreTransitionQueueOrder = function restoreTransitionQueueOrder() {
    const currentById = new Map(ctx.queue.value.map((track) => [track.id, track]));
    const restored = ctx.transitionQueueSortSnapshot
      .map((track) => currentById.get(track.id))
      .filter(Boolean);
    const snapshotIds = new Set(ctx.transitionQueueSortSnapshot.map((track) => track.id));
    const additions = ctx.queue.value.filter((track) => !snapshotIds.has(track.id));
    ctx.transitionQueueSorted.value = false;
    ctx.transitionQueueSortSnapshot = [];
    ctx.transitionQueueExpectedIds = [];
    applyQueueOrder([...restored, ...additions]);
    ctx.showShareMessage?.('Restored the previous queue order.');
  };

  async function sortTransitionQueue({ refresh = false } = {}) {
    if (ctx.transitionQueueSortBusy.value || ctx.queue.value.length < 2) return;

    const queueSignature = ctx.queue.value.map((track) => track.id).join(',');
    ctx.transitionQueueSortBusy.value = true;
    try {
      const snapshot = [...ctx.queue.value];
      const sortableTracks = snapshot.slice(0, BEST_MIX_TRACK_LIMIT);
      const untouchedTracks = snapshot.slice(BEST_MIX_TRACK_LIMIT);
      const catalogTracks = [ctx.activeTrack.value, ...sortableTracks].filter((t) => t?.id);
      if (!refresh) ctx.showShareMessage?.(`Preparing local BPM analysis for ${sortableTracks.length} queued songs…`);
      const bpmState = { settled: false, value: new Map() };
      const bpmPromise = Promise.resolve(ctx.bpmMetadata?.lookupMany?.(catalogTracks) || new Map())
        .then((m) => { bpmState.settled = true; bpmState.value = m; return m; })
        .catch(() => { bpmState.settled = true; return new Map(); });
      const [tempoByTrack, localByTrack] = await Promise.all([learnedTempoMap(), localAnalysisMap(catalogTracks)]);
      const localEvidenceCount = sortableTracks.filter((t) => hasMusicalAnalysis(analysisFor(t, tempoByTrack.get(t.id), localByTrack.get(t.id), {}))).length;
      const bpmByTrack = bpmState.settled ? bpmState.value : localEvidenceCount >= 2 ? new Map() : await bpmPromise;
      if (queueSignature !== ctx.queue.value.map((t) => t.id).join(',')) return;
      const analysisByTrack = new Map(sortableTracks.map((t) => [t.id, analysisFor(t, tempoByTrack.get(t.id), localByTrack.get(t.id), bpmByTrack.get(t.id))]));
      const currentAnalysis = analysisFor(ctx.activeTrack.value, tempoByTrack.get(ctx.activeTrack.value?.id), localByTrack.get(ctx.activeTrack.value?.id), bpmByTrack.get(ctx.activeTrack.value?.id));
      const result = bestTransitionOrder(sortableTracks, analysisByTrack, currentAnalysis);
      if (!result.comparisons) {
        if (!refresh) {
          ctx.showShareMessage?.('Best mix could not find BPM or key data for enough songs. Queue left unchanged.');
        }
        return;
      }
      const sorted = [...result.ordered, ...untouchedTracks];
      if (!refresh) ctx.transitionQueueSortSnapshot = snapshot;
      ctx.transitionQueueSorted.value = true;
      if (sorted.every((track, index) => track.id === snapshot[index]?.id)) {
        ctx.transitionQueueExpectedIds = snapshot.map((track) => track.id);
        if (!refresh) {
          ctx.showShareMessage?.('Best mix is on. This queue already has the smoothest known order.');
        }
        return;
      }
      applyQueueOrder(sorted);
      if (!refresh) {
        ctx.showShareMessage?.(
          `Best mix is on. Sorted the next ${sortableTracks.length} songs for smoother transitions.`
        );
      }
    } finally {
      ctx.transitionQueueSortBusy.value = false;
      const shouldRefresh = refreshRequested && ctx.transitionQueueSorted.value;
      refreshRequested = false;
      if (shouldRefresh) void sortTransitionQueue({ refresh: true });
    }
  }

  function refreshTransitionQueueSort() {
    if (ctx.transitionQueueSortBusy.value) {
      refreshRequested = true;
      return;
    }
    void sortTransitionQueue({ refresh: true });
  }

  ctx.toggleTransitionQueueSort = async function toggleTransitionQueueSort() {
    if (ctx.transitionQueueSorted.value) {
      ctx.restoreTransitionQueueOrder();
      return;
    }
    return sortTransitionQueue();
  };

  watch(() => ctx.queue.value.map((track) => track.id), (currentIds) => {
    if (!ctx.transitionQueueSorted.value) return;
    if (currentIds.join(',') === ctx.transitionQueueExpectedIds.join(',')) return;
    if (isOrderedSubset(currentIds, ctx.transitionQueueExpectedIds)) {
      ctx.transitionQueueExpectedIds = currentIds;
      return;
    }
    if (isOrderedQueueWithAppendedTracks(currentIds, ctx.transitionQueueExpectedIds)) {
      ctx.transitionQueueExpectedIds = currentIds;
      refreshTransitionQueueSort();
      return;
    }
    ctx.transitionQueueSorted.value = false;
    ctx.transitionQueueSortSnapshot = [];
    ctx.transitionQueueExpectedIds = [];
  });
}
