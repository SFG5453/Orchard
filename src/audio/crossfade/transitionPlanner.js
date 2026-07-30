import {
  assessTransitionTier,
  rankMixInCandidates,
  resolveMixOutAnchor
} from './transitionPolicy.js';

export const CROSSFADE_MODES = ['standard', 'smart'];

// Four bars. Overlaps are counted in beats because that is what the ear hears;
// the seconds values are rails for tempi where four bars would be absurd, not
// the primary control. Eight to sixteen beats is the range the automatic-DJ
// literature reports for stable dance material, and less for dense pop.
const AUTO_TRANSITION_MAX_BEATS = 16;
const AUTO_MIN_SECONDS = 4;
const AUTO_FAST_TRACK_MIN_SECONDS = 6;
const AUTO_TRANSITION_MAX_SECONDS = 12;
const AUTO_FALLBACK_SECONDS = 8;
const KEY_INDEX = new Map([
  ['C', 0], ['C♯', 1], ['D♭', 1], ['D', 2], ['D♯', 3], ['E♭', 3],
  ['E', 4], ['F', 5], ['F♯', 6], ['G♭', 6], ['G', 7], ['G♯', 8],
  ['A♭', 8], ['A', 9], ['A♯', 10], ['B♭', 10], ['B', 11]
]);

export function normalizeCrossfadeMode(value) {
  return CROSSFADE_MODES.includes(value) ? value : 'standard';
}

function clamp(value, min, max) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(min, Math.min(max, number)) : min;
}

function trackDurationSeconds(item = {}) {
  const direct = Number(item.durationSeconds) || 0;
  if (direct > 0) return direct;
  const parts = String(item.duration || '').trim().split(':').map(Number);
  if (!parts.length || parts.some((part) => !Number.isFinite(part))) return 0;
  return parts.reduce((total, part) => total * 60 + part, 0);
}

function itemText(item = {}) {
  return [item.type, item.title, item.subtitle, item.queueOrigin?.kind]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

function blocked(reason, detail = {}) {
  return { shouldStart: false, markerVisible: false, reason, ...detail };
}

function standardTransition(length, playbackTime, fadeSeconds, minFadeSeconds, reason = 'standard') {
  const fade = clamp(fadeSeconds, minFadeSeconds, 12);
  const transitionStart = Math.max(0, length - fade);
  return {
    shouldStart: playbackTime >= transitionStart,
    markerVisible: true,
    transitionStart,
    transitionEnd: length,
    fadeSeconds: fade,
    transitionStyle: 'equal_power',
    incomingCueTime: 0,
    incomingPlaybackRate: 1,
    reason: playbackTime >= transitionStart ? reason : `before-${reason}-window`
  };
}

function analysisReadyForTrack(analysis = {}, track = null) {
  const status = String(analysis.status || '');
  if (!status) return true;
  if (status !== 'ready') return false;
  return !analysis.trackId || !track?.id || analysis.trackId === track.id;
}

function sameAlbum(left = {}, right = {}) {
  if (left.albumId && right.albumId && left.albumId === right.albumId) return true;
  if (left.queueOrigin?.kind === 'album' && right.queueOrigin?.kind === 'album') {
    return Boolean(left.queueOrigin.title && left.queueOrigin.title === right.queueOrigin.title);
  }
  return Boolean(left.album && right.album && left.album === right.album && left.artist === right.artist);
}

function normalizedTempoRatio(currentBpm, nextBpm) {
  const current = Number(currentBpm) || 0;
  const next = Number(nextBpm) || 0;
  if (!current || !next) return 1;
  let ratio = next / current;
  while (ratio > 1.5) ratio /= 2;
  while (ratio < 0.67) ratio *= 2;
  return ratio;
}

function keyDistance(left = '', right = '') {
  const [leftRoot, leftMode] = String(left).split(' ');
  const [rightRoot, rightMode] = String(right).split(' ');
  const leftIndex = KEY_INDEX.get(leftRoot);
  const rightIndex = KEY_INDEX.get(rightRoot);
  if (!Number.isInteger(leftIndex) || !Number.isInteger(rightIndex)) return null;
  const pitchDistance = Math.min(
    (leftIndex - rightIndex + 12) % 12,
    (rightIndex - leftIndex + 12) % 12
  );
  return pitchDistance + (leftMode && rightMode && leftMode !== rightMode ? 1 : 0);
}

function harmonicallyCompatible(left = '', right = '') {
  const [leftRoot, leftMode] = String(left).split(' ');
  const [rightRoot, rightMode] = String(right).split(' ');
  const leftIndex = KEY_INDEX.get(leftRoot);
  const rightIndex = KEY_INDEX.get(rightRoot);
  if (!Number.isInteger(leftIndex) || !Number.isInteger(rightIndex)) return false;
  const distance = Math.min(
    (leftIndex - rightIndex + 12) % 12,
    (rightIndex - leftIndex + 12) % 12
  );
  if (leftMode && rightMode && leftMode !== rightMode) return distance <= 1;
  return distance <= 2 || distance === 5;
}

function trustedKey(analysis = {}) {
  const key = String(analysis.key || '');
  const confidence = Number(analysis.keyConfidence);
  if (!key || (Number.isFinite(confidence) && confidence < 0.25)) return '';
  return key;
}

function nearestTimedValue(values = [], target = 0, tolerance = Infinity, minimum = 0) {
  const candidates = values
    .map(Number)
    .filter((value) =>
      Number.isFinite(value) &&
      value >= minimum &&
      Math.abs(value - target) <= tolerance
    );
  if (!candidates.length) return null;
  return candidates.reduce((best, value) =>
    Math.abs(value - target) < Math.abs(best - target) ? value : best
  );
}

function timedValueNearOrBefore(values = [], target = 0, tolerance = Infinity, minimum = 0) {
  const candidates = values
    .map(Number)
    .filter((value) =>
      Number.isFinite(value) &&
      value >= minimum &&
      value <= target &&
      target - value <= tolerance
    );
  return candidates.length ? Math.max(...candidates) : null;
}

function alignedTransitionStart(
  analysis = {},
  target = 0,
  end = Infinity,
  preferEarlier = false,
  minimum = 0
) {
  const interval = Number(analysis.beatInterval) || (Number(analysis.bpm) > 0 ? 60 / analysis.bpm : 0);
  const phraseTolerance = Math.max(1, interval * 4);
  const downbeatTolerance = Math.max(0.75, interval * 2);
  const phrase = preferEarlier
    ? timedValueNearOrBefore(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    : nearestTimedValue(analysis.phraseBoundaries, target, phraseTolerance, minimum);
  const downbeat = preferEarlier
    ? timedValueNearOrBefore(analysis.downbeats, target, downbeatTolerance, minimum)
    : nearestTimedValue(analysis.downbeats, target, downbeatTolerance, minimum);
  const aligned = phrase ?? downbeat ?? target;
  return clamp(aligned, minimum, end);
}

function timedValueAtOrBefore(values = [], target = 0, fallback = target) {
  const candidates = values
    .map(Number)
    .filter((value) => Number.isFinite(value) && value >= 0 && value <= target);
  return candidates.length ? Math.max(...candidates) : fallback;
}

function incomingCuePoint(analysis = {}) {
  // Candidate entry points are ranked, not pattern-matched: the analyzer's
  // score plus type, downbeat, run-up and vocal terms decide which one leads.
  const ranked = rankMixInCandidates(analysis);
  if (ranked.length > 0) return ranked[0].time;
  const interval = Number(analysis.beatInterval) || (Number(analysis.bpm) > 0 ? 60 / analysis.bpm : 0);
  const downbeats = Array.isArray(analysis.downbeats) ? analysis.downbeats : [];
  const analyzedMixIn = Number(analysis.mixInTime);
  if (Number.isFinite(analyzedMixIn) && analyzedMixIn > 0) {
    return nearestTimedValue(downbeats, analyzedMixIn, Math.max(0.5, interval * 2)) ?? analyzedMixIn;
  }
  const pickup = Math.max(
    0,
    Number(analysis.introEndTime) ||
      Number(analysis.audibleStartTime ?? analysis.pickupTime) ||
      Number(analysis.firstBeat) ||
      0
  );
  if (pickup > 0 && pickup < (Number(analysis.duration) || 300) - 10) {
    const handoff = downbeats.find((beat) => Number(beat) >= pickup);
    if (handoff !== undefined) return handoff;
  }
  const phrases = Array.isArray(analysis.phraseBoundaries) ? analysis.phraseBoundaries : [];
  if (phrases.length > 1 && Number(phrases[1]) > 4) {
    return Number(phrases[1]);
  }
  if (downbeats.length >= 8) {
    return Number(downbeats[Math.min(8, downbeats.length - 1)]) || 0;
  }
  return pickup;
}

function incomingStartPoint(analysis = {}) {
  const candidates = [analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat];
  const start = candidates
    .map(Number)
    .find((value) => Number.isFinite(value) && value >= 0);
  return start ?? 0;
}

function phraseSwitch(analysis = {}, nextAnalysis = {}, length = 0) {
  const currentBpm = Number(analysis.bpm) || 0;
  const nextBpm = Number(nextAnalysis.bpm) || 0;
  const currentConfidence = Number(analysis.beatConfidence) || 0;
  const nextConfidence = Number(nextAnalysis.beatConfidence) || 0;
  const ratio = normalizedTempoRatio(currentBpm, nextBpm);
  const currentKey = trustedKey(analysis);
  const nextKey = trustedKey(nextAnalysis);
  if (
    !currentBpm ||
    !nextBpm ||
    currentConfidence < 0.55 ||
    nextConfidence < 0.55 ||
    !harmonicallyCompatible(currentKey, nextKey) ||
    ratio < 0.9 ||
    ratio > 1.1
  ) {
    return null;
  }

  const beatSeconds = 60 / currentBpm;
  const incomingPlaybackRate = Math.round(clamp(1 / ratio, 0.9, 1.1) * 10000) / 10000;
  const incomingHandoffTime = incomingCuePoint(nextAnalysis);
  const introDropTime = incomingHandoffTime / Math.max(0.8, incomingPlaybackRate);
  // The overlap covers only the incoming instrumental intro so the outgoing
  // track is fully gone by the time the incoming vocals arrive — no tail.
  const requestedOverlap = introDropTime;
  if (length <= requestedOverlap * 0.5) return null;
  // Beat-denominated like the main path; the seconds value is only a rail.
  const maximumOverlap = Math.min(
    AUTO_TRANSITION_MAX_BEATS * beatSeconds,
    AUTO_TRANSITION_MAX_SECONDS,
    length * 0.4
  );
  const actualOverlap = Math.min(requestedOverlap, maximumOverlap);
  const alignedEnd = timedValueAtOrBefore(analysis.downbeats, length, length);
  const transitionEnd = length - alignedEnd <= beatSeconds * 4.5 ? alignedEnd : length;
  const rawTransitionStart = transitionEnd - actualOverlap;
  const earliestTransitionStart = transitionEnd - maximumOverlap;
  const transitionStart = clamp(
    nearestTimedValue(
      analysis.downbeats,
      rawTransitionStart,
      beatSeconds * 0.75,
      earliestTransitionStart
    ) ?? rawTransitionStart,
    earliestTransitionStart,
    transitionEnd - beatSeconds * 4
  );
  const overlap = transitionEnd - transitionStart;
  // The fade runs through the incoming intro and closes on its drop, so it
  // spans the whole overlap rather than starting once the drop has landed.
  const handoffStartSeconds = 0;
  const handoffDuration = overlap;
  const transitionBeats = Math.round(overlap / beatSeconds);
  // Clamped at zero, which shortens the run-up rather than moving the drop.
  const incomingCueTime = Math.max(0, incomingHandoffTime - overlap * incomingPlaybackRate);

  return {
    transitionStart,
    transitionEnd,
    fadeSeconds: overlap,
    handoffDuration,
    handoffStartSeconds,
    incomingCueTime,
    incomingHandoffTime,
    incomingPlaybackRate,
    pickupSeconds: Math.max(0, Number(nextAnalysis.audibleStartTime ?? nextAnalysis.pickupTime) || 0),
    transitionBeats,
    bassSwap: true,
    transitionStyle: 'dj_blend'
  };
}

function adaptiveOverlap(analysis = {}, nextAnalysis = {}) {
  const currentBpm = Number(analysis.bpm) || 0;
  const nextBpm = Number(nextAnalysis.bpm) || 0;
  if (!currentBpm || !nextBpm) {
    return { overlap: AUTO_FALLBACK_SECONDS, transitionBeats: 0, incomingPlaybackRate: 1 };
  }

  const ratio = normalizedTempoRatio(currentBpm, nextBpm);
  const distance = keyDistance(trustedKey(analysis), trustedKey(nextAnalysis));
  const vocalConflict = Number(analysis.vocalProbability) >= 0.62 &&
    Number(nextAnalysis.vocalProbability) >= 0.62;
  const transitionBeats = !vocalConflict &&
    (Math.abs(1 - ratio) > 0.07 || (distance !== null && distance > 4)) ? 16 : 8;
  const beatSeconds = 60 / currentBpm;
  // Eight beats can be under four seconds on faster material. Keep a little
  // more real-time overlap there so smart mixes do not turn into abrupt swaps.
  const minimumOverlap = currentBpm >= 140
    ? AUTO_FAST_TRACK_MIN_SECONDS
    : AUTO_MIN_SECONDS;

  return {
    overlap: clamp(
      transitionBeats * beatSeconds,
      minimumOverlap,
      AUTO_TRANSITION_MAX_SECONDS
    ),
    transitionBeats,
    incomingPlaybackRate: ratio >= 0.9 && ratio <= 1.1
      ? Math.round(clamp(1 / ratio, 0.9, 1.1) * 10000) / 10000
      : 1
  };
}

export function planTransition({
  analysis = {},
  currentTime = 0,
  currentTrack = null,
  duration = 0,
  fadeSeconds = 6,
  minFadeSeconds = 1,
  mode = 'standard',
  nextAnalysis = {},
  nextTrack = null
} = {}) {
  const length = Math.max(Number(duration) || 0, trackDurationSeconds(currentTrack));
  const playbackTime = Math.max(0, Number(currentTime) || 0);
  if (length <= 0) return blocked('no-duration');

  const standardFade = clamp(fadeSeconds, minFadeSeconds, 12);
  if (normalizeCrossfadeMode(mode) !== 'smart') {
    return standardTransition(length, playbackTime, standardFade, minFadeSeconds);
  }

  if (length < 45) return blocked('short-duration-guard', { transitionStart: length, transitionEnd: length });
  const analyzedContentEnd = Number(analysis.contentEndTime) || length;
  const finalMixAnchor = analyzedContentEnd > 0 && analyzedContentEnd <= length
    ? analyzedContentEnd
    : length;
  // Where the transition ends. An interior anchor has to earn its place: it is
  // only taken when what it skips is silence or a short tail, never when a
  // minute of music is still to come. See MAX_DISCARDED_MUSIC_SECONDS.
  const mixOutAnchor = resolveMixOutAnchor(analysis, {
    contentEnd: finalMixAnchor,
    duration: length
  });
  const hasInteriorMixOut = mixOutAnchor.time < finalMixAnchor - 1;
  if (sameAlbum(currentTrack, nextTrack) && !hasInteriorMixOut) {
    const transitionStart = Math.max(0, length - 0.45);
    return {
      shouldStart: playbackTime >= transitionStart,
      markerVisible: true,
      transitionStart,
      transitionEnd: length,
      fadeSeconds: 0.12,
      transitionStyle: 'gapless',
      incomingCueTime: 0,
      incomingPlaybackRate: 1,
      reason: playbackTime >= transitionStart ? 'same-album-gapless' : 'before-gapless-window'
    };
  }

  const text = `${itemText(currentTrack)} ${itemText(nextTrack)}`;
  if (/\b(podcast|episode|audiobook|live|concert|performance)\b/.test(text)) {
    return blocked('blocked-speech-or-live');
  }

  if (
    !analysisReadyForTrack(analysis, currentTrack) ||
    !analysisReadyForTrack(nextAnalysis, nextTrack)
  ) {
    return standardTransition(
      length,
      playbackTime,
      standardFade,
      minFadeSeconds,
      'smart-analysis-fallback'
    );
  }

  const preferredMixAnchor = Math.min(length, mixOutAnchor.time);
  const mixAnchor = playbackTime >= preferredMixAnchor - 0.05 &&
    preferredMixAnchor < finalMixAnchor - 1
    ? finalMixAnchor
    : preferredMixAnchor;

  // The degradation ladder's bottom rung: when tempo is missing or neither
  // beat grid is trusted, no beat-quantized DJ move is allowed. The stored
  // analysis still decides *where* the transition happens -- a plain
  // equal-power fade ending at the analyzed mix-out anchor, cued past the
  // incoming track's lead-in silence -- it just no longer decides *how*.
  const policy = assessTransitionTier({ analysis, nextAnalysis });
  if (policy.tier === 'plain_crossfade') {
    const transitionStart = Math.max(0, mixAnchor - standardFade);
    return {
      shouldStart: playbackTime >= transitionStart,
      markerVisible: true,
      transitionStart,
      transitionEnd: mixAnchor,
      fadeSeconds: mixAnchor - transitionStart,
      transitionStyle: 'equal_power',
      incomingCueTime: incomingStartPoint(nextAnalysis),
      incomingPlaybackRate: 1,
      policyReasons: policy.reasons,
      reason: playbackTime >= transitionStart
        ? 'smart-plain-crossfade'
        : 'before-plain-crossfade-window'
    };
  }

  const switchPlan = phraseSwitch(analysis, nextAnalysis, mixAnchor);
  if (switchPlan) {
    return {
      shouldStart: playbackTime >= switchPlan.transitionStart,
      markerVisible: true,
      ...switchPlan,
      reason: playbackTime >= switchPlan.transitionStart
        ? 'smart-phrase-switch'
        : 'before-phrase-switch'
    };
  }

  const { overlap, incomingPlaybackRate, transitionBeats } = adaptiveOverlap(analysis, nextAnalysis);
  const mixEnd = mixAnchor;
  const nextLength = trackDurationSeconds(nextTrack);
  const currentBpm = Number(analysis.bpm) || 0;
  const nextBpm = Number(nextAnalysis.bpm) || 0;
  const handoffBpm = currentBpm || nextBpm;
  // An overlap is a musical length, so it is bounded in beats first. Bounding
  // it in seconds meant a faster track got a *longer* mix: at 140 BPM the
  // sixteen-second cap ran to thirty-seven beats, over nine bars, which stops
  // sounding like a transition and starts sounding like two records at once.
  // The seconds cap survives only as a rail for tempi where four bars is
  // absurdly long.
  const maximumOverlap = Math.min(
    handoffBpm > 0 ? (AUTO_TRANSITION_MAX_BEATS * 60) / handoffBpm : AUTO_TRANSITION_MAX_SECONDS,
    AUTO_TRANSITION_MAX_SECONDS,
    mixEnd * 0.4,
    nextLength > 0 ? nextLength * 0.4 : AUTO_TRANSITION_MAX_SECONDS
  );
  const currentConfidence = Number(analysis.beatConfidence) || 0;
  const nextConfidence = Number(nextAnalysis.beatConfidence) || 0;
  const sameBeatBlend = currentBpm > 0 && nextBpm > 0 &&
    Math.abs(1 - normalizedTempoRatio(currentBpm, nextBpm)) <= 0.05 &&
    (currentConfidence >= 0.2 || nextConfidence >= 0.2);
  const handoffBeats = sameBeatBlend ? 8 : 4;
  const beatSeconds = handoffBpm > 0 ? 60 / handoffBpm : 0.5;
  const handoffSeconds = handoffBpm > 0
    ? clamp((handoffBeats * 60) / handoffBpm, 2, sameBeatBlend ? 6 : 5)
    : 4;
  const analyzedPickup = Number(nextAnalysis.audibleStartTime ?? nextAnalysis.pickupTime);
  const pickupSeconds = Number.isFinite(analyzedPickup) && analyzedPickup >= 0
    ? analyzedPickup
    : 0;
  const incomingHandoffTime = incomingCuePoint(nextAnalysis);
  const rawIncomingCueTime = incomingStartPoint(nextAnalysis);
  const analyzedIncomingHandoff = Number(nextAnalysis.mixInTime);
  const hasIncomingPreroll = Number.isFinite(analyzedIncomingHandoff) &&
    analyzedIncomingHandoff > rawIncomingCueTime + 0.5;
  const incomingCueTime = hasIncomingPreroll ? rawIncomingCueTime : incomingHandoffTime;
  const introPreroll = Math.max(
    0,
    (hasIncomingPreroll ? incomingHandoffTime - incomingCueTime : 0) /
      Math.max(0.8, incomingPlaybackRate)
  );

  let finalIncomingCueTime = incomingCueTime;
  let handoffStartSeconds;
  let handoffDuration;
  let transitionStart;

  if (sameBeatBlend && beatSeconds > 0) {
    // AutoMix-style transition for matching/near-matching BPM:
    //   Phase 1: Silent preroll — incoming plays from 0:00 at bed gain, HP-filtered
    //   Phase 2: Crossfade handoff — outgoing fades to silence at the incoming drop
    //
    // The incoming track is cued from its start (incomingCueTime = 0 or pickup)
    // and plays its full intro underneath. The incomingHandoffTime (intro drop,
    // ~16s / 32 beats for dance/pop) determines when the main handoff occurs.
    // The overlap covers only the instrumental intro so the outgoing track is
    // fully gone by the time the incoming vocals arrive.

    const introDropTime = incomingHandoffTime / Math.max(0.8, incomingPlaybackRate);
    // The overlap covers only the incoming instrumental intro so the outgoing
    // track reaches silence exactly when the incoming vocals arrive.
    const totalOverlap = clamp(
      introDropTime,
      Math.min(12, maximumOverlap),
      maximumOverlap
    );

    const targetStart = Math.max(0, mixEnd - totalOverlap);
    const earliestTransitionStart = Math.max(0, mixEnd - maximumOverlap);
    transitionStart = alignedTransitionStart(
      analysis,
      targetStart,
      mixEnd - 0.05,
      true,
      earliestTransitionStart
    );

    const alignedOverlap = mixEnd - transitionStart;

    // The outgoing track fades across the whole overlap and reaches silence as
    // the incoming one drops, so the swap is the overlap rather than a window
    // opening once the drop has already landed.
    handoffStartSeconds = 0;
    // A short intro cannot cover the whole overlap; cueing at zero shortens the
    // run-up instead of dragging the drop away from where it was analysed.
    finalIncomingCueTime = Math.max(0, incomingHandoffTime - alignedOverlap * incomingPlaybackRate);
    handoffDuration = alignedOverlap;
  } else {
    const desiredOverlap = Math.max(overlap, introPreroll + handoffSeconds * 0.42);
    const actualOverlap = clamp(
      desiredOverlap,
      Math.min(handoffSeconds, maximumOverlap),
      maximumOverlap
    );
    const targetStart = Math.max(0, mixEnd - actualOverlap);
    const earliestTransitionStart = Math.max(0, mixEnd - maximumOverlap);
    transitionStart = alignedTransitionStart(
      analysis,
      targetStart,
      mixEnd - 0.05,
      desiredOverlap > overlap + 0.5,
      earliestTransitionStart
    );
    const alignedOverlap = mixEnd - transitionStart;
    // Filtered blends fade across the overlap too, so the departing track is
    // gone by the time the incoming one is running on its own.
    handoffStartSeconds = 0;
    handoffDuration = alignedOverlap;
    finalIncomingCueTime = hasIncomingPreroll
      ? Math.max(0, incomingHandoffTime - alignedOverlap * incomingPlaybackRate)
      : finalIncomingCueTime;
  }

  const alignedOverlap = mixEnd - transitionStart;
  const hasBassContent = (analysis.lowEnergyCurve?.length || 0) > 0 || (nextAnalysis.lowEnergyCurve?.length || 0) > 0;
  return {
    shouldStart: playbackTime >= transitionStart,
    markerVisible: true,
    transitionStart,
    transitionEnd: mixEnd,
    fadeSeconds: alignedOverlap,
    handoffDuration,
    handoffStartSeconds,
    incomingCueTime: finalIncomingCueTime,
    incomingHandoffTime,
    incomingPlaybackRate,
    pickupSeconds,
    transitionBeats,
    bassSwap: sameBeatBlend || hasBassContent,
    transitionStyle: sameBeatBlend ? 'dj_blend' : 'dj_filter',
    reason: playbackTime >= transitionStart ? 'smart-duration' : 'before-smart-duration'
  };
}
