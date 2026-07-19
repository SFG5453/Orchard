import { ref, watch } from 'vue';
import { loadLearnedAudioProfiles } from '../../audio/engine/audioProfileStore.js';

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
    const pitchDistance = Math.min(
      (leftIndex - rightIndex + 12) % 12,
      (rightIndex - leftIndex + 12) % 12
    );
    return Math.min(1, 0.35 + pitchDistance / 10);
  }
  const leftCircle = (leftIndex * 7) % 12;
  const rightCircle = (rightIndex * 7) % 12;
  const circleDistance = Math.min(
    (leftCircle - rightCircle + 12) % 12,
    (rightCircle - leftCircle + 12) % 12
  );
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

export function transitionCost(left = {}, right = {}) {
  let weightedCost = 0;
  let totalWeight = 0;
  const tempoRatio = normalizedTempoRatio(left.bpm, right.bpm);
  if (tempoRatio) {
    const weight = 4 * Math.sqrt(
      confidence(left.beatConfidence, 0.35) * confidence(right.beatConfidence, 0.35)
    );
    const cost = Math.min(1.5, Math.abs(Math.log2(tempoRatio)) / Math.log2(1.2));
    weightedCost += cost * weight;
    totalWeight += weight;
  }

  const keyCost = harmonicCost(left.key, right.key);
  if (keyCost !== null) {
    const weight = 2.4 * Math.sqrt(
      confidence(left.keyConfidence, 0.35) * confidence(right.keyConfidence, 0.35)
    );
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
    const conflict = clamp01((leftVocal - 0.5) * 2) * clamp01((rightVocal - 0.5) * 2);
    weightedCost += conflict * weight;
    totalWeight += weight;
  }

  return totalWeight > 0 ? weightedCost / totalWeight : null;
}

function orderAnalyzedSegment(segment, initialAnalysis) {
  const remaining = [...segment];
  const ordered = [];
  let comparisons = 0;
  let previous = initialAnalysis;
  if (!hasMusicalAnalysis(previous) && remaining.length) {
    const [first] = remaining.splice(0, 1);
    ordered.push(first);
    previous = first.analysis;
  }

  while (remaining.length) {
    const comparable = remaining
      .map((candidate, index) => ({ candidate, cost: transitionCost(previous, candidate.analysis), index }))
      .filter((entry) => entry.cost !== null);
    let bestIndex = 0;
    if (comparable.length) {
      comparisons += 1;
      comparable.sort((left, right) =>
        left.cost - right.cost || left.candidate.originalIndex - right.candidate.originalIndex
      );
      bestIndex = comparable[0].index;
    }
    const [selected] = remaining.splice(bestIndex, 1);
    ordered.push(selected);
    previous = selected.analysis;
  }
  return { comparisons, ordered };
}

export function bestTransitionOrder(queue, analysisByTrack, initialAnalysis = {}) {
  const output = [];
  let comparisons = 0;
  let segment = [];
  let previous = initialAnalysis;

  function flushSegment() {
    if (!segment.length) return;
    const result = orderAnalyzedSegment(segment, previous);
    output.push(...result.ordered.map((entry) => entry.track));
    comparisons += result.comparisons;
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
  return { comparisons, ordered: output };
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

export function installQueueTransitionSort(ctx) {
  ctx.transitionQueueSorted = ref(false);
  ctx.transitionQueueSortBusy = ref(false);
  ctx.transitionQueueSortSnapshot = [];
  ctx.transitionQueueExpectedIds = [];
  let learnedTempoPromise = null;

  function analysisFor(track, learnedTempo, cached = {}) {
    const smart = ctx.crossfadeAnalysisByTrack?.get(track?.id) || {};
    const activeAnalysis = track?.id === ctx.activeTrack.value?.id &&
      ctx.crossfadeAnalysis.value?.status === 'ready'
      ? ctx.crossfadeAnalysis.value
      : {};
    const sources = [smart, activeAnalysis, cached, track || {}];
    const tempoSource = sources.find((source) => Number(source?.bpm || source?.tempo) > 0);
    const keySource = sources.find((source) => parsedKey(source?.key));
    const loudnessSource = sources.find((source) => finiteOrNull(source?.loudnessLufs, -69) !== null);
    const energySource = sources.find((source) => Array.isArray(source?.energyCurve) && source.energyCurve.length);
    const vocalSource = sources.find((source) => finiteOrNull(source?.vocalProbability, -0.001) !== null);
    return {
      bpm: Number(tempoSource?.bpm || tempoSource?.tempo || learnedTempo) || 0,
      beatConfidence: Number(tempoSource?.beatConfidence) || (tempoSource ? 0.35 : (learnedTempo ? 0.25 : 0)),
      key: keySource?.key || '',
      keyConfidence: Number(keySource?.keyConfidence) || (keySource ? 0.35 : 0),
      loudnessLufs: loudnessSource?.loudnessLufs ?? null,
      energyCurve: energySource?.energyCurve || [],
      vocalProbability: vocalSource?.vocalProbability ?? null
    };
  }

  async function learnedTempoMap() {
    learnedTempoPromise ||= loadLearnedAudioProfiles()
      .then((profiles) => new Map(profiles.map((profile) => [profile.trackId, profile.tempo])))
      .catch(() => new Map());
    return learnedTempoPromise;
  }

  async function cachedAnalysisMap(tracks) {
    const getCached = globalThis.orchardAudioAnalysis?.get;
    if (typeof getCached !== 'function') return new Map();
    const unique = Array.from(new Map(tracks.filter((track) => track?.id).map((track) => [track.id, track])).values());
    const entries = await Promise.all(unique.map(async (track) => [
      track.id,
      await getCached(track.id).catch(() => null)
    ]));
    return new Map(entries.filter(([, analysis]) => analysis));
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

  ctx.toggleTransitionQueueSort = async function toggleTransitionQueueSort() {
    if (ctx.transitionQueueSorted.value) {
      ctx.restoreTransitionQueueOrder();
      return;
    }
    if (ctx.transitionQueueSortBusy.value || ctx.queue.value.length < 2) return;

    const queueSignature = ctx.queue.value.map((track) => track.id).join(',');
    ctx.transitionQueueSortBusy.value = true;
    try {
      const [tempoByTrack, cachedByTrack] = await Promise.all([
        learnedTempoMap(),
        cachedAnalysisMap([ctx.activeTrack.value, ...ctx.queue.value])
      ]);
      if (queueSignature !== ctx.queue.value.map((track) => track.id).join(',')) return;
      const snapshot = [...ctx.queue.value];
      const analysisByTrack = new Map(snapshot.map((track) => [
        track.id,
        analysisFor(track, tempoByTrack.get(track.id), cachedByTrack.get(track.id))
      ]));
      const currentAnalysis = analysisFor(
        ctx.activeTrack.value,
        tempoByTrack.get(ctx.activeTrack.value?.id),
        cachedByTrack.get(ctx.activeTrack.value?.id)
      );
      const result = bestTransitionOrder(snapshot, analysisByTrack, currentAnalysis);
      if (!result.comparisons) {
        ctx.showShareMessage?.('Best mix needs BPM or key analysis for more songs. Queue left unchanged.');
        return;
      }
      const sorted = result.ordered;
      if (sorted.every((track, index) => track.id === snapshot[index]?.id)) {
        ctx.showShareMessage?.('This queue already has the smoothest known order.');
        return;
      }
      ctx.transitionQueueSortSnapshot = snapshot;
      ctx.transitionQueueSorted.value = true;
      applyQueueOrder(sorted);
      ctx.showShareMessage?.(`Sorted ${sorted.length} songs for smoother transitions.`);
    } finally {
      ctx.transitionQueueSortBusy.value = false;
    }
  };

  watch(() => ctx.queue.value.map((track) => track.id), (currentIds) => {
    if (!ctx.transitionQueueSorted.value) return;
    if (currentIds.join(',') === ctx.transitionQueueExpectedIds.join(',')) return;
    if (isOrderedSubset(currentIds, ctx.transitionQueueExpectedIds)) {
      ctx.transitionQueueExpectedIds = currentIds;
      return;
    }
    ctx.transitionQueueSorted.value = false;
    ctx.transitionQueueSortSnapshot = [];
    ctx.transitionQueueExpectedIds = [];
  });
}
