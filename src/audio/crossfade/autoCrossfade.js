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

import { normalizeCrossfadeMode, planTransition } from './transitionPlanner.js';
import { equalPowerMixWeights } from './crossfadeVisualState.js';

export const AUTO_CROSSFADE_DEFAULTS = {
  fadeSeconds: 6,
  mode: 'standard',
  minFadeSeconds: 1,
  maxFadeSeconds: 45,
  analysisWindowSeconds: 45,
  triggerWindowSeconds: 14,
  fallbackSeconds: 3
};

function clamp01(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(1, number));
}

export function clampCrossfadeSeconds(value) {
  const number = Math.round(Number(value));
  if (!Number.isFinite(number)) return AUTO_CROSSFADE_DEFAULTS.fadeSeconds;
  return Math.max(
    AUTO_CROSSFADE_DEFAULTS.minFadeSeconds,
    Math.min(12, number)
  );
}

export { normalizeCrossfadeMode };

export function alignTransitionToPlayback(transition = {}, playbackTime = 0) {
  const transitionStart = Number(transition.transitionStart);
  const transitionEnd = Number(transition.transitionEnd);
  const currentTime = Math.max(0, Number(playbackTime) || 0);
  if (!Number.isFinite(transitionStart) || !Number.isFinite(transitionEnd)) return transition;
  if (['silence_trim', 'normal_boundary'].includes(transition.transitionStyle)) {
    // Boundary handoffs have no overlap to shorten and no incoming timeline to
    // advance. A late poll executes the same boundary immediately.
    return transition;
  }
  if (currentTime >= transitionEnd - 0.05) return null;

  const lateBy = Math.max(0, currentTime - transitionStart);
  if (lateBy <= 0) return transition;

  const incomingRate = Math.max(0.8, Math.min(1.2, Number(transition.incomingPlaybackRate) || 1));
  const originalHandoffStart = Math.max(0, Number(transition.handoffStartSeconds) || 0);
  const originalHandoffDuration = Math.max(
    0.05,
    Number(transition.handoffDuration) || Number(transition.fadeSeconds) || 0.05
  );
  const remainingFade = Math.max(0.05, transitionEnd - currentTime);
  const handoffStartSeconds = Math.max(0, originalHandoffStart - lateBy);
  const handoffEndSeconds = Math.max(0.05, originalHandoffStart + originalHandoffDuration - lateBy);

  return {
    ...transition,
    transitionStart: currentTime,
    fadeSeconds: remainingFade,
    handoffStartSeconds,
    handoffDuration: Math.max(
      0.05,
      Math.min(remainingFade - handoffStartSeconds, handoffEndSeconds - handoffStartSeconds)
    ),
    incomingCueTime: Math.max(
      0,
      (Number(transition.incomingCueTime) || 0) + lateBy * incomingRate
    )
  };
}

export function createAutoCrossfade({ analyzer, settings = {} } = {}) {
  const config = {
    ...AUTO_CROSSFADE_DEFAULTS,
    ...settings,
    mode: normalizeCrossfadeMode(settings.mode),
    fadeSeconds: clampCrossfadeSeconds(settings.fadeSeconds ?? AUTO_CROSSFADE_DEFAULTS.fadeSeconds)
  };
  let active = false;
  let completeTimer = 0;
  let promoteTimer = 0;
  let tempoTimer = 0;
  let tempoStartTimer = 0;
  let mixFrame = 0;
  let completeResolve = null;
  let activeCleanup = null;
  let activeFromAudio = null;
  let activeToAudio = null;
  let targetVolume = 1;
  let transitionSequence = 0;

  function isActive() {
    return active;
  }

  function setTargetVolume(value) {
    targetVolume = clamp01(value);
    if (!active) return;
    analyzer?.setVolume?.(activeFromAudio, targetVolume);
    analyzer?.setVolume?.(activeToAudio, targetVolume);
  }

  function setFadeSeconds(value) {
    config.fadeSeconds = clampCrossfadeSeconds(value);
  }

  function setMode(value) {
    config.mode = normalizeCrossfadeMode(value);
  }

  function transitionPlan(options = {}) {
    return planTransition({
      ...options,
      fadeSeconds: config.fadeSeconds,
      maxFadeSeconds: config.maxFadeSeconds,
      minFadeSeconds: config.minFadeSeconds,
      mode: config.mode,
      nextAnalysis: options.nextAnalysis
    });
  }

  function shouldStart({
    albumSequential,
    currentAudio,
    currentTime,
    currentTrack,
    duration,
    hasNext,
    isPlaying,
    isSeeking,
    nextTrack,
    analysis,
    nextAnalysis
  }) {
    if (active || !currentAudio || !hasNext || !isPlaying || isSeeking) return false;
    return transitionPlan({
      albumSequential,
      currentAudio,
      currentTime,
      currentTrack,
      duration,
      nextTrack,
      analysis,
      nextAnalysis
    }).shouldStart;
  }

  function cancel() {
    transitionSequence += 1;
    window.clearTimeout(completeTimer);
    window.clearTimeout(promoteTimer);
    window.clearTimeout(tempoStartTimer);
    window.clearInterval(tempoTimer);
    window.cancelAnimationFrame?.(mixFrame);
    completeTimer = 0;
    promoteTimer = 0;
    tempoTimer = 0;
    tempoStartTimer = 0;
    mixFrame = 0;
    active = false;
    activeCleanup?.();
    activeCleanup = null;
    analyzer?.resetMixElement?.(activeFromAudio);
    analyzer?.resetMixElement?.(activeToAudio);
    if (activeFromAudio) activeFromAudio.playbackRate = 1;
    if (activeToAudio) activeToAudio.playbackRate = 1;
    activeFromAudio = null;
    activeToAudio = null;
    completeResolve?.();
    completeResolve = null;
  }

  async function start({ fromAudio, toAudio, transition = null, volume, onPromote, onComplete, onError, onMixState }) {
    if (active || !fromAudio || !toAudio) {
      return false;
    }

    const playbackTransition = alignTransitionToPlayback(transition || {}, fromAudio.currentTime);
    if (!playbackTransition) return false;
    transition = playbackTransition;

    const sequence = ++transitionSequence;
    setTargetVolume(volume);
    active = true;
    activeFromAudio = fromAudio;
    activeToAudio = toAudio;
    let promoted = false;
    let promotionError = null;
    activeCleanup = () => {
      if (promoted) {
        analyzer?.setVolume?.(fromAudio, 0);
        analyzer?.setVolume?.(toAudio, targetVolume);
        fromAudio.pause();
      } else {
        analyzer?.setVolume?.(fromAudio, targetVolume);
        analyzer?.setVolume?.(toAudio, 0);
        toAudio.pause();
      }
      analyzer?.resetMixElement?.(fromAudio);
      analyzer?.resetMixElement?.(toAudio);
      fromAudio.playbackRate = 1;
      toAudio.playbackRate = 1;
    };
    const promote = () => {
      if (promoted || !active || sequence !== transitionSequence) return;
      onPromote?.();
      promoted = true;
    };

    try {
      analyzer?.connectElement(fromAudio);
      analyzer?.connectElement(toAudio);
      await analyzer?.resume?.();
      if (!active || sequence !== transitionSequence) return false;

      fromAudio.volume = 1;
      toAudio.volume = 1;
      analyzer?.setVolume?.(fromAudio, targetVolume);
      analyzer?.setVolume?.(toAudio, targetVolume);
      const incomingCueTime = Math.max(0, Number(transition?.incomingCueTime) || 0);
      const incomingRate = Math.max(
        0.8,
        Math.min(1.2, Number(transition?.incomingPlaybackRate) || 1)
      );
      toAudio.currentTime = incomingCueTime;
      fromAudio.preservesPitch = true;
      fromAudio.mozPreservesPitch = true;
      fromAudio.webkitPreservesPitch = true;
      toAudio.preservesPitch = true;
      toAudio.mozPreservesPitch = true;
      toAudio.webkitPreservesPitch = true;
      fromAudio.playbackRate = 1;
      toAudio.playbackRate = incomingRate;
      const requestedFadeSeconds = transition?.fadeSeconds || config.fadeSeconds;
      const remainingSeconds = Number(fromAudio.duration) - Number(fromAudio.currentTime);
      const fadeSeconds = Number.isFinite(remainingSeconds) && remainingSeconds > 0
        ? Math.min(requestedFadeSeconds, Math.max(0.05, remainingSeconds))
        : requestedFadeSeconds;
      // Mute the incoming deck before it makes a sound. scheduleCrossfade only
      // zeroes this gain at its own start time, one lead-time into the future,
      // and the element is connected at unity -- so everything between play()
      // resolving and that point was audible at full level. That is the burst
      // heard at the top of every transition.
      if (!analyzer?.setMixVolume?.(toAudio, 0)) {
        throw new Error('Transition elements are outside the audio graph');
      }
      await toAudio.play();
      if (!active || sequence !== transitionSequence) return false;

      if (['silence_trim', 'normal_boundary'].includes(transition?.transitionStyle)) {
        // Preload the incoming element while muted, then make one non-overlap
        // deck switch at the requested boundary. Rewinding to the authoritative
        // cue at the switch prevents the muted preparation lead from consuming
        // any incoming music.
        await new Promise((resolve) => {
          completeResolve = resolve;
          const delayMs = Math.max(
            0,
            (Number(transition.transitionEnd) - Number(fromAudio.currentTime)) * 1000
          );
          completeTimer = window.setTimeout(() => {
            completeTimer = 0;
            completeResolve = null;
            try {
              toAudio.currentTime = incomingCueTime;
              analyzer?.setMixVolume?.(fromAudio, 0);
              analyzer?.setMixVolume?.(toAudio, 1);
              fromAudio.pause();
              promote();
            } catch (error) {
              promotionError = error;
            }
            resolve();
          }, delayMs);
        });
        if (promotionError) throw promotionError;
        if (!active || sequence !== transitionSequence) return false;

        toAudio.volume = 1;
        fromAudio.removeAttribute('src');
        fromAudio.load();
        analyzer?.setVolume?.(fromAudio, 0);
        analyzer?.setVolume?.(toAudio, targetVolume);
        analyzer?.resetMixElement?.(fromAudio);
        analyzer?.resetMixElement?.(toAudio);
        fromAudio.playbackRate = 1;
        toAudio.playbackRate = 1;
        active = false;
        activeCleanup = null;
        activeFromAudio = null;
        activeToAudio = null;
        onComplete?.();
        return true;
      }

      const timing = analyzer?.scheduleCrossfade?.({
        fromAudio,
        toAudio,
        targetVolume,
        duration: fadeSeconds,
        handoffDuration: transition?.handoffDuration,
        handoffStartSeconds: transition?.handoffStartSeconds,
        bassSwap: transition?.bassSwap,
        transitionStyle: transition?.transitionStyle,
        choreography: transition?.choreography
      });
      if (!timing) throw new Error('Web Audio crossfade is unavailable');

      const visualDuration = Math.max(0.001, timing.endTime - timing.startTime);
      const visualHandoffProgress = clamp01(
        (timing.promotionTime - timing.startTime) / visualDuration
      );
      const publishMixState = (complete = false) => {
        if (typeof onMixState !== 'function') return;
        const contextTime = analyzer?.currentTime?.() || timing.startTime;
        const progress = complete
          ? 1
          : clamp01((contextTime - timing.startTime) / visualDuration);
        const fallback = equalPowerMixWeights(progress);
        const outgoingGain = analyzer?.mixVolume?.(fromAudio);
        const incomingGain = analyzer?.mixVolume?.(toAudio);
        try {
          onMixState({
            complete,
            contextTime,
            endTime: timing.endTime,
            handoffProgress: visualHandoffProgress,
            incomingGain: complete ? 1 : Number.isFinite(incomingGain) ? incomingGain : fallback.incomingGain,
            outgoingGain: complete ? 0 : Number.isFinite(outgoingGain) ? outgoingGain : fallback.outgoingGain,
            progress,
            started: complete || contextTime >= timing.startTime,
            startTime: timing.startTime
          });
        } catch {
          // Visual subscribers cannot be allowed to interrupt audio scheduling.
        }
      };
      const followMixState = () => {
        mixFrame = 0;
        if (!active || sequence !== transitionSequence) return;
        publishMixState(false);
        if ((analyzer?.currentTime?.() || timing.startTime) >= timing.endTime) return;
        mixFrame = window.requestAnimationFrame?.(followMixState) || 0;
      };
      publishMixState(false);
      mixFrame = window.requestAnimationFrame?.(followMixState) || 0;

      if (incomingRate !== 1) {
        const startTempoRelease = () => {
          const releaseSeconds = Math.min(
            8,
            Math.max(2.5, (Number(transition?.handoffDuration) || fadeSeconds) * 0.45)
          );
          const rampMs = releaseSeconds * 1000;
          const startedAt = performance.now();
          tempoTimer = window.setInterval(() => {
            const progress = Math.min(1, (performance.now() - startedAt) / rampMs);
            const smooth = progress * progress * (3 - 2 * progress);
            toAudio.playbackRate = Math.round((incomingRate + (1 - incomingRate) * smooth) * 100) / 100;
            if (progress >= 1) {
              window.clearInterval(tempoTimer);
              tempoTimer = 0;
              toAudio.playbackRate = 1;
            }
          }, Math.max(40, rampMs / 48));
        };
        const releaseSeconds = Math.min(
          8,
          Math.max(2.5, (Number(transition?.handoffDuration) || fadeSeconds) * 0.45)
        );
        const releaseAt = Math.max(timing.handoffStart, timing.endTime - releaseSeconds);
        const delayMs = Math.max(0, (releaseAt - (analyzer?.currentTime?.() || 0)) * 1000);
        tempoStartTimer = window.setTimeout(() => {
          tempoStartTimer = 0;
          startTempoRelease();
        }, delayMs);
      }

      const promotionAt = Math.max(
        timing.startTime,
        Math.min(timing.endTime, Number(timing.promotionTime) || timing.handoffStart)
      );
      const promotionDelayMs = Math.max(
        0,
        (promotionAt - (analyzer?.currentTime?.() || 0)) * 1000
      );
      if (promotionDelayMs <= 5) {
        promote();
      } else {
        promoteTimer = window.setTimeout(() => {
          promoteTimer = 0;
          try {
            promote();
          } catch (error) {
            promotionError = error;
            completeResolve?.();
          }
        }, promotionDelayMs);
      }

      await new Promise((resolve) => {
        completeResolve = resolve;
        const remainingMs = Math.max(0, (timing.endTime - (analyzer?.currentTime?.() || 0)) * 1000);
        completeTimer = window.setTimeout(() => {
          completeResolve = null;
          resolve();
        }, remainingMs + 30);
      });

      window.clearTimeout(promoteTimer);
      promoteTimer = 0;
      if (promotionError) throw promotionError;
      if (!active || sequence !== transitionSequence) return false;
      promote();

      if (active) {
        window.cancelAnimationFrame?.(mixFrame);
        mixFrame = 0;
        publishMixState(true);
        toAudio.volume = 1;
        fromAudio.pause();
        fromAudio.removeAttribute('src');
        fromAudio.load();
        analyzer?.setVolume?.(fromAudio, 0);
        analyzer?.setVolume?.(toAudio, targetVolume);
        analyzer?.resetMixElement?.(fromAudio);
        analyzer?.resetMixElement?.(toAudio);
        fromAudio.playbackRate = 1;
        toAudio.playbackRate = 1;
      }

      active = false;
      completeTimer = 0;
      promoteTimer = 0;
      window.clearTimeout(tempoStartTimer);
      window.clearInterval(tempoTimer);
      window.cancelAnimationFrame?.(mixFrame);
      tempoTimer = 0;
      tempoStartTimer = 0;
      mixFrame = 0;
      activeCleanup = null;
      activeFromAudio = null;
      activeToAudio = null;
      onComplete?.();
      return true;
    } catch (error) {
      if (sequence !== transitionSequence) return false;
      activeCleanup?.();
      active = false;
      window.clearTimeout(completeTimer);
      completeTimer = 0;
      window.clearTimeout(promoteTimer);
      promoteTimer = 0;
      window.clearTimeout(tempoStartTimer);
      window.clearInterval(tempoTimer);
      window.cancelAnimationFrame?.(mixFrame);
      tempoTimer = 0;
      tempoStartTimer = 0;
      mixFrame = 0;
      completeResolve = null;
      activeCleanup = null;
      activeFromAudio = null;
      activeToAudio = null;
      onError?.(error);
      return false;
    }
  }

  return {
    cancel,
    isActive,
    setFadeSeconds,
    setMode,
    setTargetVolume,
    shouldStart,
    start,
    transitionPlan,
    settings: config
  };
}
