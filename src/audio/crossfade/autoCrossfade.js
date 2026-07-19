import { normalizeCrossfadeMode, planTransition } from './transitionPlanner.js';

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
  let completeResolve = null;
  let activeCleanup = null;
  let activeFromAudio = null;
  let activeToAudio = null;
  let targetVolume = 1;

  function isActive() {
    return active;
  }

  function setTargetVolume(value) {
    targetVolume = clamp01(value);
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
    window.clearTimeout(completeTimer);
    window.clearTimeout(promoteTimer);
    window.clearTimeout(tempoStartTimer);
    window.clearInterval(tempoTimer);
    completeTimer = 0;
    promoteTimer = 0;
    tempoTimer = 0;
    tempoStartTimer = 0;
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

  async function start({ fromAudio, toAudio, transition = null, volume, onPromote, onComplete, onError }) {
    if (active || !fromAudio || !toAudio) {
      return false;
    }

    const playbackTransition = alignTransitionToPlayback(transition || {}, fromAudio.currentTime);
    if (!playbackTransition) return false;
    transition = playbackTransition;

    active = true;
    activeFromAudio = fromAudio;
    activeToAudio = toAudio;
    setTargetVolume(volume);
    let promoted = false;
    let promotionError = null;

    try {
      analyzer?.connectElement(fromAudio);
      analyzer?.connectElement(toAudio);
      await analyzer?.resume?.();

      fromAudio.volume = 1;
      toAudio.volume = 1;
      analyzer?.setVolume?.(fromAudio, targetVolume);
      analyzer?.setVolume?.(toAudio, 0);
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
      await toAudio.play();
      const timing = analyzer?.scheduleCrossfade?.({
        fromAudio,
        toAudio,
        targetVolume,
        duration: fadeSeconds,
        handoffDuration: transition?.handoffDuration,
        handoffStartSeconds: transition?.handoffStartSeconds,
        transitionStyle: transition?.transitionStyle
      });
      if (!timing) throw new Error('Web Audio crossfade is unavailable');

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
        if (promoted || !active) return;
        onPromote?.();
        promoted = true;
      };
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
      if (!active) return false;
      promote();

      if (active) {
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
      tempoTimer = 0;
      tempoStartTimer = 0;
      activeCleanup = null;
      activeFromAudio = null;
      activeToAudio = null;
      onComplete?.();
      return true;
    } catch (error) {
      activeCleanup?.();
      active = false;
      window.clearTimeout(completeTimer);
      completeTimer = 0;
      window.clearTimeout(promoteTimer);
      promoteTimer = 0;
      window.clearTimeout(tempoStartTimer);
      window.clearInterval(tempoTimer);
      tempoTimer = 0;
      tempoStartTimer = 0;
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
