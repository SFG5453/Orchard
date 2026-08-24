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

import { planPairTransition } from './pairTransitionPlanner.js';

export const CROSSFADE_MODES = ['standard', 'smart'];

export function normalizeCrossfadeMode(value) {
  return CROSSFADE_MODES.includes(value) ? value : 'standard';
}

function clamp(value, minimum, maximum) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(minimum, Math.min(maximum, number)) : minimum;
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

function audibleEnd(analysis = {}, fallback = 0) {
  const candidates = [
    analysis.audibleRange?.end,
    analysis.contentEndTime,
    analysis.duration,
    fallback
  ].map(Number).filter((value) => Number.isFinite(value) && value > 0);
  return candidates.length ? Math.min(fallback || Infinity, candidates[0]) : fallback;
}

function audibleStart(analysis = {}) {
  const candidates = [
    analysis.audibleRange?.start,
    analysis.audibleStartTime,
    analysis.pickupTime,
    analysis.firstBeat
  ].map(Number).filter((value) => Number.isFinite(value) && value >= 0);
  return candidates.length ? Math.min(...candidates) : 0;
}

export function transitionFromPairFallback(
  pairPlan,
  analysis,
  nextAnalysis,
  length,
  playbackTime,
  minFadeSeconds = 1
) {
  const fallback = pairPlan.fallback;
  const finalEnd = audibleEnd(analysis, length) || length;
  let transitionEnd = clamp(fallback.outgoingEnd, 0, length);
  let fadeSeconds = Math.max(0, Number(fallback.durationSeconds) || 0);
  let incomingCueTime = Math.max(0, Number(fallback.incomingCue) || 0);
  let late = false;

  // Playback time is a scheduling concern, not another musical choice. If the
  // selected exit has already passed, keep the attached fallback shape but
  // move it to the final usable boundary rather than running another planner.
  if (playbackTime >= transitionEnd - 0.05 && transitionEnd < finalEnd - 0.05) {
    transitionEnd = finalEnd;
    incomingCueTime = audibleStart(nextAnalysis);
    late = true;
  }
  if (fadeSeconds <= 0 && transitionEnd > 0) fadeSeconds = minFadeSeconds;
  fadeSeconds = Math.min(fadeSeconds, transitionEnd);
  const transitionStart = Math.max(0, transitionEnd - fadeSeconds);
  const shouldStart = playbackTime >= transitionStart;
  const reasonBase = late ? 'smart-pair-late-fallback' : 'smart-pair-fallback';
  return {
    shouldStart,
    markerVisible: transitionEnd > 0,
    transitionStart,
    transitionEnd,
    fadeSeconds,
    handoffDuration: fadeSeconds,
    handoffStartSeconds: 0,
    incomingCueTime,
    incomingHandoffTime: pairPlan.incoming.handoff,
    incomingPlaybackRate: 1,
    pickupSeconds: audibleStart(nextAnalysis),
    transitionBeats: 0,
    bassSwap: false,
    transitionStyle: fallback.transitionStyle === 'normal' ? 'equal_power' : fallback.transitionStyle,
    policyReasons: pairPlan.diagnostics?.selected?.gates || [],
    fallbackReason: pairPlan.fallbackReason,
    fallback,
    pairPlan,
    reason: shouldStart ? reasonBase : `before-${reasonBase}-window`
  };
}

export function planTransition({
  albumSequential = false,
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
  if (length < 45) {
    return blocked('short-duration-guard', { transitionStart: length, transitionEnd: length });
  }

  // Album playthroughs preserve the record's own spacing. Queue context owns
  // this exception, so no automatic pair search is allowed to override it.
  if (albumSequential && sameAlbum(currentTrack, nextTrack)) {
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

  const pairPlan = planPairTransition({
    analysis,
    nextAnalysis,
    duration: length,
    nextDuration: trackDurationSeconds(nextTrack)
  });
  return transitionFromPairFallback(
    pairPlan,
    analysis,
    nextAnalysis,
    length,
    playbackTime,
    minFadeSeconds
  );
}
