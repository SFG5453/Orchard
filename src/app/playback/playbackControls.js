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

import { nextTick } from 'vue';
import { playlistPreviousState } from './playbackCollectionQueue.js';
import { advanceToQueueEntry, rewindToHistoryEntry } from './queueLayout.js';
import { reliablePlaybackDuration } from './playbackDuration.js';
import {
  createSmartCrossfadeMixPresentation,
  updateSmartCrossfadeMixPresentation
} from './smartCrossfadeMixPresentation.js';
import {
  WSOLA_PREPARE_LEAD_SECONDS,
  WSOLA_START_LEAD_SECONDS,
  wsolaProcessingCompatible
} from '../../audio/crossfade/wsolaCrossfade.js';
import {
  smartPairPlanningBlockReason,
  transitionFromPairFallback
} from '../../audio/crossfade/transitionPlanner.js';

const LYRIC_AUTO_SCROLL_RESUME_DELAY_MS = 1800;

function isUnavailableTrackError(error) {
  return /\b(?:unavailable|not available|no playable (?:audio |video )?format|video unavailable|private video|removed by uploader)\b/i
    .test(String(error?.message || error || ''));
}

export function queueAfterTransitionPromotion(queue = [], incomingTrackId = '') {
  return queue.filter((track) => track?.id !== incomingTrackId);
}

export function playbackNeedsFreshStream(media, playbackError = '') {
  return Boolean(
    playbackError || media?.ended || media?.error || media?.networkState === 3
  );
}

function albumTrackNumber(track) {
  const number = Number(String(track?.index ?? '').trim());
  return Number.isFinite(number) && number > 0 ? number : 0;
}

// True only for an album being listened to start to finish in its own order.
// Anything else -- shuffle, Best Mix, a playlist, a hand-built queue, two album
// siblings dragged next to each other -- is a mix and should be transitioned.
export function isAlbumPlaythrough({
  currentTrack,
  nextTrack,
  shuffleEnabled = false,
  bestMixSorted = false
} = {}) {
  if (shuffleEnabled || bestMixSorted) return false;

  const origin = currentTrack?.queueOrigin;
  const nextOrigin = nextTrack?.queueOrigin;
  if (origin?.kind !== 'album' || nextOrigin?.kind !== 'album') return false;
  if (!origin.title || origin.title !== nextOrigin.title) return false;
  if ((origin.artist || '') !== (nextOrigin.artist || '')) return false;

  // Album rows carry their track number. When both are known the pair has to be
  // consecutive; when the catalog omitted them, the shared album origin is the
  // best evidence available and gapless stands.
  const current = albumTrackNumber(currentTrack);
  const next = albumTrackNumber(nextTrack);
  if (!current || !next) return true;
  return next === current + 1;
}

export function installPlaybackControls(ctx) {
  let crossfadeClockTimer = 0;
  let fullscreenPlayerDomActive = false;

  ctx.dismissSmartCrossfadeMix = function dismissSmartCrossfadeMix() {
    window.clearTimeout(ctx.smartCrossfadeMixTimer);
    ctx.smartCrossfadeMixTimer = 0;
    if (!ctx.smartCrossfadeMix.value.visible) return;
    ctx.smartCrossfadeMix.value = {
      ...ctx.smartCrossfadeMix.value,
      visible: false,
      phase: 'idle',
      progress: 0,
      preparationProgress: 0,
      secondsUntilStart: 0,
      outgoingGain: 1,
      incomingGain: 0,
      incomingWeight: 0
    };
  };

  ctx.prepareSmartCrossfadeMix = function prepareSmartCrossfadeMix(details, secondsUntilStart) {
    const current = ctx.smartCrossfadeMix.value;
    const fromId = String(details.fromTrack?.id || '');
    const toId = String(details.toTrack?.id || '');
    const samePair = current.visible && current.from?.id === fromId && current.to?.id === toId;
    if (samePair && current.phase !== 'preparing') return;

    const presentation = samePair
      ? current
      : createSmartCrossfadeMixPresentation({
        id: ++ctx.smartCrossfadeMixSequence,
        currentArtwork: ctx.nowArtworkImage.value,
        ...details,
        phase: 'preparing',
        secondsUntilStart
      });
    ctx.smartCrossfadeMix.value = {
      ...presentation,
      phase: 'preparing',
      progress: 0,
      preparationProgress: Math.max(0, Math.min(1, 1 - (Number(secondsUntilStart) || 0) / 8)),
      secondsUntilStart: Math.max(0, Number(secondsUntilStart) || 0),
      outgoingGain: 1,
      incomingGain: 0,
      incomingWeight: 0
    };
  };

  ctx.clearPreparingSmartCrossfadeMix = function clearPreparingSmartCrossfadeMix() {
    if (ctx.smartCrossfadeMix.value.phase === 'preparing') ctx.dismissSmartCrossfadeMix();
  };

  ctx.showSmartCrossfadeMix = function showSmartCrossfadeMix(details) {
    window.clearTimeout(ctx.smartCrossfadeMixTimer);
    ctx.smartCrossfadeMixTimer = 0;
    const current = ctx.smartCrossfadeMix.value;
    const samePair = current.visible &&
      current.from?.id === String(details.fromTrack?.id || '') &&
      current.to?.id === String(details.toTrack?.id || '');
    const presentation = createSmartCrossfadeMixPresentation({
      id: samePair ? current.id : ++ctx.smartCrossfadeMixSequence,
      currentArtwork: ctx.nowArtworkImage.value,
      ...details
    });
    ctx.smartCrossfadeMix.value = presentation;
  };

  ctx.updateSmartCrossfadeMix = function updateSmartCrossfadeMix(sample) {
    if (!ctx.smartCrossfadeMix.value.visible) return;
    ctx.smartCrossfadeMix.value = updateSmartCrossfadeMixPresentation(
      ctx.smartCrossfadeMix.value,
      sample
    );
  };

  ctx.completeSmartCrossfadeMix = function completeSmartCrossfadeMix() {
    if (!ctx.smartCrossfadeMix.value.visible) return;
    ctx.updateSmartCrossfadeMix({
      complete: true,
      handoffProgress: ctx.smartCrossfadeMix.value.handoffProgress,
      incomingGain: 1,
      outgoingGain: 0,
      progress: 1,
      started: true
    });
    // The mix itself is already complete. This short hold only lets the incoming
    // artwork settle onto the normal resting geometry before its duplicate is
    // removed, making the handoff mathematically seamless.
    window.clearTimeout(ctx.smartCrossfadeMixTimer);
    ctx.smartCrossfadeMixTimer = window.setTimeout(ctx.dismissSmartCrossfadeMix, 420);
  };

  ctx.cancelActiveCrossfade = function cancelActiveCrossfade(reason = 'unspecified') {
    const wasActive = Boolean(ctx.autoCrossfade?.isActive?.()) ||
      Boolean(ctx.wsolaCrossfade?.isActive?.());
    ctx.autoCrossfade?.cancel?.();
    ctx.wsolaCrossfade?.cancel?.(reason);
    if (ctx.smartCrossfadeMix?.value?.visible) ctx.dismissSmartCrossfadeMix();
    return wasActive;
  };

  ctx.recoverPrematureAudioEnd = function recoverPrematureAudioEnd(media) {
    const track = ctx.activeTrack.value;
    const expected = reliablePlaybackDuration(ctx, media, track);
    const stoppedAt = Number(media?.currentTime) || ctx.currentTime.value || 0;
    const remaining = expected - stoppedAt;
    if (!track?.id || expected < 45 || remaining <= Math.max(12, expected * 0.08)) return false;

    ctx.autoCrossfade.cancel();
    if (!track.streamRefreshTried) {
      return Boolean(ctx.retryAudioStream(track, { refreshStream: true, resumeAt: stoppedAt }));
    }
    if (!track.playbackFallbackTried) {
      return Boolean(ctx.retryAudioWithAlternateFormat(track, { resumeAt: stoppedAt }));
    }
    ctx.isPlaying.value = false;
    ctx.playbackError.value = `Playback stopped early at ${Math.round(stoppedAt)} seconds.`;
    return true;
  };

  ctx.syncAudioPlaybackClock = function syncAudioPlaybackClock() {
    const media = ctx.currentPlaybackElement();
    if (!media || ctx.isSeeking.value) return;

    // The video player publishes its own time and duration, so mirroring them
    // from here would fight it. Polling for a transition is the other half of
    // this clock's job and applies to a music video just as much.
    if (!ctx.activeTrackIsVideo.value) {
      const playbackTime = Number(media.currentTime);
      if (Number.isFinite(playbackTime)) {
        ctx.currentTime.value = playbackTime;
        ctx.seekPosition.value = playbackTime;
      }

      const mediaDuration = reliablePlaybackDuration(ctx, media);
      if (mediaDuration) ctx.duration.value = mediaDuration;
    }

    if (!media.paused && !media.ended) void ctx.maybeStartAutoCrossfade();
  };

  ctx.startCrossfadeClock = function startCrossfadeClock() {
    if (crossfadeClockTimer || !ctx.crossfadeEnabled.value) return;
    crossfadeClockTimer = window.setInterval(ctx.syncAudioPlaybackClock, 120);
  };

  ctx.stopCrossfadeClock = function stopCrossfadeClock() {
    window.clearInterval(crossfadeClockTimer);
    crossfadeClockTimer = 0;
  };

  async function waitForFullscreenPlayer() {
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await nextTick();
      if (ctx.fullscreenPlayerRef.value) return ctx.fullscreenPlayerRef.value;
      await new Promise((resolve) => window.requestAnimationFrame(resolve));
    }

    return ctx.fullscreenPlayerRef.value;
  }

  ctx.openFullscreenPlayer = async function openFullscreenPlayer() {
    if (!ctx.activeTrack.value || ctx.fullscreenPlayerOpen.value) return;

    fullscreenPlayerDomActive = false;
    ctx.fullscreenPlayerOpen.value = true;
    const player = await waitForFullscreenPlayer();

    try {
      await window.orchardWindow?.setFullscreen(true);
    } catch {
      // Browser fullscreen remains available outside Electron or if the shell rejects the request.
    }

    if (!player?.requestFullscreen || document.fullscreenElement) return;

    try {
      await player.requestFullscreen();
      fullscreenPlayerDomActive = true;
    } catch {
      // The in-window overlay is still a complete player when fullscreen is unavailable.
    }
  };

  ctx.closeFullscreenPlayer = async function closeFullscreenPlayer() {
    fullscreenPlayerDomActive = false;
    ctx.fullscreenPlayerOpen.value = false;

    if (document.fullscreenElement && document.exitFullscreen) {
      try {
        await document.exitFullscreen();
      } catch {
        // The overlay is already closed; the native window can still leave fullscreen.
      }
    }

    try {
      await window.orchardWindow?.setFullscreen(false);
    } catch {
      // Browser-only builds have no native window fullscreen state to restore.
    }
  };

  ctx.onFullscreenPlayerChange = function onFullscreenPlayerChange() {
    if (document.fullscreenElement === ctx.fullscreenPlayerRef.value) {
      fullscreenPlayerDomActive = true;
      return;
    }

    if (!document.fullscreenElement && fullscreenPlayerDomActive && ctx.fullscreenPlayerOpen.value) {
      void ctx.closeFullscreenPlayer();
    }
  };

  ctx.togglePlayback = function togglePlayback() {
    if (ctx.activePlaybackTarget?.value && ctx.activePlaybackTarget.value !== 'local') {
      ctx.sendConnectTargetCommand?.({ type: 'play-pause' });
      return;
    }
    if (ctx.listeningParty.value?.status === 'connected' && !ctx.listeningPartyIsHost.value) {
      ctx.sendListeningPartyRequest({ action: ctx.isPlaying.value ? 'pause' : 'play' });
      return;
    }
    ctx.cancelActiveCrossfade('toggle-playback');
    const media = ctx.currentPlaybackElement();
    if (ctx.activeTrack.value && (!media?.src || playbackNeedsFreshStream(media, ctx.playbackError.value))) {
      ctx.playTrack(ctx.activeTrack.value, {
        mediaKind: ctx.activeMediaKind.value,
        queueSource: [ctx.activeTrack.value, ...ctx.queue.value],
        queueAlreadyShuffled: Boolean(ctx.shuffleEnabled?.value),
        refreshStream: true,
        skipHistory: true
      });
      return;
    }
    if (!media?.src) return;
    const videoAudio = ctx.activeTrackIsVideo.value ? ctx.videoAudioRef.value : null;

    if (media.paused) {
      ctx.playbackError.value = '';
      ctx.audioAnalyzer.resume().catch(() => {});
      ctx.syncVideoCompanionAudio();
      const playRequest = videoAudio?.src
        ? Promise.all([media.play(), videoAudio.play()])
        : media.play();
      playRequest.catch((error) => {
        if (ctx.isInterruptedPlaybackRequest(error)) return;
        ctx.playbackError.value = error.message;
      });
    } else {
      media.pause();
      videoAudio?.pause();
    }
  };

  ctx.seekRelative = function seekRelative(offsetSeconds) {
    ctx.seek((ctx.currentPlaybackElement()?.currentTime || ctx.currentTime.value || 0) + offsetSeconds);
  };

  ctx.playHistoryTrack = function playHistoryTrack(track) {
    if (!track?.id) return;
    ctx.playTrack(track, { queueSource: [track] });
  };

  ctx.continuousQueueSectionLabel = function continuousQueueSectionLabel(section) {
    if (section === 'previous') return 'Previous';
    if (section === 'current') return 'Now playing';
    return 'Next up';
  };

  ctx.playContinuousQueueEntry = function playContinuousQueueEntry(entry, options = {}) {
    if (!entry?.track?.id) return;
    if (ctx.activePlaybackTarget?.value && ctx.activePlaybackTarget.value !== 'local') {
      const index = ctx.queue.value.findIndex((t) => t.id === entry.track.id);
      if (index >= 0) {
        ctx.sendConnectTargetCommand?.({ type: 'play-queue-index', value: index });
      } else {
        ctx.sendConnectTargetCommand?.({ type: 'play-track', value: entry.track });
      }
      return;
    }
    if (entry.section === 'current') {
      ctx.seek(0);
      return;
    }
    if (!options.fromListeningPartyRequest && ctx.requestListeningPartyHostControl?.({
      action: 'play-continuous-entry',
      section: entry.section,
      historyIndex: entry.historyIndex,
      queueIndex: entry.queueIndex,
      trackId: entry.track.id
    })) {
      return;
    }

    const playbackState = {
      history: ctx.history.value,
      activeTrack: ctx.activeTrack.value,
      queue: ctx.queue.value
    };
    const next = entry.section === 'previous'
      ? rewindToHistoryEntry({ ...playbackState, historyIndex: entry.historyIndex })
      : advanceToQueueEntry({ ...playbackState, queueIndex: entry.queueIndex });
    if (!next) return;

    ctx.cancelActiveCrossfade();
    ctx.history.value = next.history;
    ctx.queue.value = next.queue;
    ctx.syncManualQueueOrder();
    return ctx.playTrack(next.track, {
      listeningPartySync: true,
      preserveQueue: true,
      skipHistory: true,
      sessionAction: entry.section === 'previous' ? 'previous' : 'manual'
    });
  };

  ctx.minimizeVideoPlayer = function minimizeVideoPlayer() {
    ctx.videoPlayerMinimized.value = true;
  };

  ctx.expandVideoPlayer = function expandVideoPlayer() {
    ctx.videoPlayerMinimized.value = false;
  };

  ctx.repeatQueueSource = function repeatQueueSource() {
    const seen = new Set();
    const playlistTracks = ctx.playbackPlaylistContext?.value?.allTracks;
    const activeTrackId = ctx.activeTrack.value?.id;
    const hasActivePlaylistTrack = activeTrackId && playlistTracks?.some((track) => track.id === activeTrackId);
    const ordered = hasActivePlaylistTrack
      ? playlistTracks.filter(ctx.isPlayableTrack)
      : [
          ...ctx.history.value.slice().reverse(),
          ctx.activeTrack.value
        ].filter(ctx.isPlayableTrack);

    return ordered.filter((track) => {
      if (seen.has(track.id)) return false;
      seen.add(track.id);
      return true;
    });
  };

  ctx.playNext = async function playNext(options = {}) {
    if (ctx.activePlaybackTarget?.value && ctx.activePlaybackTarget.value !== 'local') {
      ctx.sendConnectTargetCommand?.({ type: 'next' });
      return;
    }
    if (!options.fromListeningPartyRequest && ctx.listeningParty?.value?.status === 'connected' && !ctx.listeningPartyIsHost?.value) {
      ctx.requestListeningPartyHostControl?.({ action: 'next' });
      return;
    }
    if (!options.fromListeningPartyRequest && !options.fromEnded && ctx.requestListeningPartyHostControl?.({ action: 'next' })) return;
    ctx.cancelActiveCrossfade('play-next');
    if (ctx.repeatMode.value === 'one' && ctx.activeTrack.value && !options.skipRepeatOne) {
      await ctx.playTrack(ctx.activeTrack.value, {
        mediaKind: ctx.activeMediaKind.value,
        queueSource: [ctx.activeTrack.value, ...ctx.queue.value],
        queueAlreadyShuffled: Boolean(ctx.shuffleEnabled?.value),
        refreshStream: true,
        skipHistory: true
      });
      return;
    }

    let restartingRepeatQueue = false;
    let [next, ...remainingQueue] = ctx.queue.value;
    if (!next && ctx.repeatMode.value === 'queue' && ctx.activeTrack.value) {
      const repeatQueue = ctx.repeatQueueSource();
      const repeatedQueue = ctx.shuffleEnabled.value ? ctx.shuffleItems(repeatQueue) : repeatQueue;
      if (
        repeatedQueue.length > 1 &&
        repeatedQueue[0]?.id === ctx.activeTrack.value.id
      ) {
        const nextTrackIndex = repeatedQueue.findIndex((track) => track.id !== ctx.activeTrack.value.id);
        [repeatedQueue[0], repeatedQueue[nextTrackIndex]] = [repeatedQueue[nextTrackIndex], repeatedQueue[0]];
      }
      ctx.queue.value = repeatedQueue;
      [next, ...remainingQueue] = repeatedQueue;
      restartingRepeatQueue = Boolean(next);
    }
    if (!next && ctx.autoplayEnabled.value) {
      await ctx.ensureAutoplayQueue({ force: true });
      [next, ...remainingQueue] = ctx.queue.value;
    }
    if (!next) {
      ctx.clearNextPreload();
      return;
    }

    while (next) {
      let resolved = ctx.preloadedTrackMatches(next) ? ctx.nextTrackPreload.value?.resolved : null;
      if (!resolved) {
        try {
          resolved = await ctx.resolvePlayableTrack(next);
        } catch (error) {
          if (!isUnavailableTrackError(error)) {
            ctx.playbackError.value = error.message;
            return;
          }
          ctx.removeUnavailableQueueTrack?.(next);
          [next, ...remainingQueue] = ctx.queue.value;
          continue;
        }
      }

      ctx.playTrack(next, {
        queueSource: [next, ...remainingQueue],
        queueAlreadyShuffled: ctx.shuffleEnabled.value,
        resetHistory: restartingRepeatQueue,
        resetPlaylistCycle: restartingRepeatQueue,
        resolved,
        sessionAction: options.fromEnded ? 'ended' : 'manual'
      });
      return;
    }
    ctx.clearNextPreload();
  };

  // Confirms the incoming track is preloaded on the standby element and
  // returns its resolved stream, forcing the preload when the transition is
  // imminent. Shared by both transition engines.
  async function confirmNextPreload(next, toAudio) {
    if (!ctx.preloadedTrackMatches(next) || !toAudio.src) {
      const didPreload = await ctx.preloadNextTrack({ force: true });
      if (!didPreload || !ctx.preloadedTrackMatches(next) || !toAudio.src) {
        return null;
      }
    }
    return ctx.nextTrackPreload.value?.resolved || null;
  }

  // A transition promotes the incoming track without going through
  // playbackResolve, which is what normally sets the media kind. Left alone
  // after transitioning out of a music video, activeTrackIsVideo stays true and
  // currentPlaybackElement keeps pointing at a video that is no longer the
  // source of anything audible.
  function retireOutgoingVideo(nextTrack, outgoingVideo) {
    ctx.activeMediaKind.value = nextTrack?.mediaKind || 'audio';
    if (outgoingVideo) outgoingVideo.pause();
  }

  // Routes a smart-mode transition to the beat-matched WSOLA engine when the
  // pairing qualifies. The structured fallback is adapted from the same pair
  // plan, so a native refusal never launches a second musical planning pass.
  async function maybeRunWsolaTransition({ next, fromAudio, toAudio, playbackTime, mediaDuration }) {
    const engine = ctx.wsolaCrossfade;
    if (!engine) return { status: 'fallback', fallback: null };
    const fromVideo = ctx.activeTrackIsVideo.value ? ctx.videoRef.value : null;
    // An overlap already playing must hold, never fall back: the rendered
    // buffer already contains the incoming track, so letting the legacy engine
    // start its own fade on the same standby element plays it a second time.
    if (engine.isActive()) return { status: 'hold', fallback: null };
    const fromTrackId = ctx.activeTrack.value?.id;
    const preparationStatus = engine.preparationStatus(fromTrackId, next.id);
    const capturedPlan = engine.preparationPlan?.(fromTrackId, next.id) || null;
    const plan = capturedPlan || engine.plan({
      fromTrackId,
      toTrackId: next.id,
      analysis: ctx.crossfadeAnalysis.value,
      nextAnalysis: ctx.nextCrossfadeAnalysis.value,
      duration: mediaDuration,
      nextDuration: Number(next.durationSeconds) || 0,
      mode: ctx.crossfadeMode.value
    });
    const attachedFallback = (sourcePlan = plan) => {
      if (!sourcePlan?.pairPlan?.fallback) return null;
      return transitionFromPairFallback(
        sourcePlan.pairPlan,
        ctx.crossfadeAnalysis.value,
        ctx.nextCrossfadeAnalysis.value,
        mediaDuration,
        playbackTime,
        1
      );
    };
    const fallback = (sourcePlan = plan) => ({
      status: 'fallback',
      fallback: attachedFallback(sourcePlan)
    });
    if (preparationStatus === 'failed') return fallback();
    const trackGains = ctx.audioEngineTrackGains?.value || {};
    if (!wsolaProcessingCompatible({
      normalizationEnabled: ctx.volumeNormalizationEnabled?.value,
      audioEngineConfig: ctx.audioEngineConfig?.value,
      outgoingGainDb: trackGains[fromTrackId],
      incomingGainDb: trackGains[next.id]
    })) {
      return fallback();
    }
    if (!plan.ok) return fallback();

    // Once preparation captures a plan, timing remains pinned to that same
    // object even if analysis metadata is enriched while rendering finishes.
    const prepared = preparationStatus === 'ready'
      ? engine.preparedTransition(fromTrackId, next.id)
      : null;
    const untilStart = (prepared?.plan?.transitionStart ?? plan.transitionStart) - playbackTime;
    const visualPlan = prepared?.plan || plan;
    const visualTransition = {
      transitionStart: visualPlan.transitionStart,
      transitionEnd: visualPlan.transitionEnd,
      fadeSeconds: visualPlan.overlapSeconds,
      transitionStyle: 'wsola_blend',
      transitionBeats: visualPlan.beats,
      incomingCueTime: visualPlan.incomingCueTime,
      incomingPlaybackRate: 1,
      choreography: visualPlan.choreography,
      handoffFraction: visualPlan.bassSwapFraction
    };
    if (untilStart > 0 && untilStart <= 8) {
      ctx.prepareSmartCrossfadeMix({
        fromTrack: ctx.activeTrack.value,
        toTrack: next,
        transition: visualTransition,
        analysis: ctx.crossfadeAnalysis.value,
        nextAnalysis: ctx.nextCrossfadeAnalysis.value
      }, untilStart);
    } else if (untilStart > 8) {
      ctx.clearPreparingSmartCrossfadeMix();
    }
    if (untilStart > WSOLA_START_LEAD_SECONDS) {
      if (untilStart <= WSOLA_PREPARE_LEAD_SECONDS &&
          preparationStatus === 'idle') {
        const fromUrl = fromAudio.currentSrc || fromAudio.src || '';
        const toUrl = ctx.nextTrackPreload.value?.resolved?.streamUrl || '';
        if (fromUrl && toUrl) {
          void engine.prepare({ fromTrackId, toTrackId: next.id, fromUrl, toUrl, plan });
        } else if (!toUrl) {
          void ctx.preloadNextTrack();
        }
      }
      return { status: 'hold', fallback: null };
    }
    if (preparationStatus !== 'ready') {
      return untilStart > -0.2
        ? { status: 'hold', fallback: null }
        : fallback();
    }

    const resolved = await confirmNextPreload(next, toAudio);
    if (!resolved) return fallback(prepared?.plan);
    // Start against the plan the buffer was rendered with; a fresher plan may
    // have shifted after metadata enrichment and would misplace the downbeats.
    if (!prepared) return fallback();
    const previousTrack = ctx.activeTrack.value;
    const nextTrack = ctx.activeTrackFromResolved(next, resolved);
    const nextDeck = ctx.activeAudioDeck.value === 'main' ? 'next' : 'main';
    const transition = {
      transitionStart: prepared.plan.transitionStart,
      transitionEnd: prepared.plan.transitionEnd,
      fadeSeconds: prepared.plan.overlapSeconds,
      transitionStyle: 'wsola_blend',
      transitionBeats: prepared.plan.beats,
      incomingCueTime: prepared.plan.incomingCueTime,
      incomingPlaybackRate: 1,
      choreography: prepared.plan.choreography,
      handoffFraction: prepared.plan.bassSwapFraction,
      reason: 'wsola-beat-match'
    };
    ctx.showSmartCrossfadeMix({
      fromTrack: previousTrack,
      toTrack: nextTrack,
      transition,
      analysis: ctx.crossfadeAnalysis.value,
      nextAnalysis: ctx.nextCrossfadeAnalysis.value
    });

    const didStart = await engine.start({
      fromAudio,
      toAudio,
      plan: prepared.plan,
      render: prepared.render,
      volume: ctx.volume.value,
      onPromote: () => {
        const nextQueue = queueAfterTransitionPromotion(ctx.queue.value, next.id);
        ctx.finishYouTubeHistory?.();
        ctx.markPlaylistTrackPlayed?.(previousTrack);
        if (previousTrack?.id) {
          ctx.history.value.unshift(previousTrack);
          ctx.history.value = ctx.history.value.slice(0, 30);
        }
        ctx.nextPreloadRequest += 1;
        ctx.nextTrackPreload.value = null;
        ctx.activeAudioDeck.value = nextDeck;
        ctx.activeTrack.value = nextTrack;
        retireOutgoingVideo(nextTrack, fromVideo);
        ctx.startYouTubeHistory?.(nextTrack.youtubeVideoId || nextTrack.id);
        ctx.promoteCrossfadeAnalysis(nextTrack.id);
        if (ctx.crossfadeAnalysis.value.status !== 'ready') {
          void ctx.analyzeCurrentCrossfadeTrack(nextTrack, resolved.streamUrl, nextTrack.durationSeconds || 0);
        }
        ctx.queue.value = nextQueue;
        if (ctx.shuffleEnabled.value && ctx.shuffleSourceQueue.value.length) {
          ctx.shuffleSourceQueue.value = ctx.shuffleSourceQueue.value.filter((track) => track.id !== nextTrack.id);
        }
        void ctx.refillPlaylistQueue?.();
        ctx.currentTime.value = toAudio.currentTime || 0;
        ctx.seekPosition.value = ctx.currentTime.value;
        ctx.duration.value = reliablePlaybackDuration(ctx, toAudio, nextTrack);
        ctx.buffering.value = false;
        ctx.isPlaying.value = true;
        ctx.recordSessionEvent?.('crossfade', nextTrack, {
          fromTrack: previousTrack,
          queue: nextQueue,
          transitionMode: ctx.crossfadeMode.value,
          transitionReason: transition.reason,
          transitionStyle: transition.transitionStyle,
          progressSeconds: ctx.currentTime.value,
          durationSeconds: ctx.duration.value
        });
      },
      onComplete: () => {
        ctx.completeSmartCrossfadeMix();
        ctx.clearAudioElement(fromAudio);
        // The companion audio stream is what fromAudio refers to for a music
        // video; the picture is a second element and has to be released too.
        if (fromVideo && fromVideo !== fromAudio) ctx.clearMediaElement(fromVideo);
        void ctx.preloadNextTrack();
      },
      onError: (error) => {
        ctx.dismissSmartCrossfadeMix();
        ctx.playbackError.value = error.message;
      },
      onMixState: ctx.updateSmartCrossfadeMix
    });
    if (!didStart) {
      ctx.dismissSmartCrossfadeMix();
      return fallback(prepared.plan);
    }
    return { status: 'started', fallback: null };
  }

  ctx.maybeStartAutoCrossfade = async function maybeStartAutoCrossfade(options = {}) {
    if (ctx.listeningParty?.value?.status === 'connected' && !ctx.listeningPartyIsHost?.value) {
      return false;
    }
    if (!ctx.crossfadeEnabled.value) return false;
    if (ctx.sleepTimerMode.value === 'end-track' || ctx.sleepTimerVolumeFactor.value < 1) {
      return false;
    }
    if (ctx.repeatMode.value === 'one') return false;
    // Covers the forced end-of-track handoff too: only one engine may ever own
    // the standby element, or the incoming track is heard from both.
    if (ctx.wsolaCrossfade?.isActive?.()) return false;

    const next = ctx.queue.value[0];
    // For a music video the audible element is the companion audio stream, or
    // the video itself when it carries its own. Either is in the audio graph;
    // the video element is only the picture, and gets stopped on promotion.
    const fromAudio = ctx.currentPlaybackAudioElement();
    const fromVideo = ctx.activeTrackIsVideo.value ? ctx.videoRef.value : null;
    const toAudio = ctx.standbyAudio();

    if (!next?.id || !fromAudio || !toAudio) {
      ctx.clearPreparingSmartCrossfadeMix();
      return false;
    }
    const mediaCurrentTime = Number(fromAudio.currentTime);
    const mediaDuration = reliablePlaybackDuration(ctx, fromAudio);
    const albumSequential = isAlbumPlaythrough({
      currentTrack: ctx.activeTrack.value,
      nextTrack: next,
      shuffleEnabled: Boolean(ctx.shuffleEnabled?.value),
      bestMixSorted: Boolean(ctx.transitionQueueSorted?.value)
    });

    // A beat-matched blend is still a mix. An album playthrough asks for the
    // record's own spacing, so the WSOLA route is skipped and the planner is
    // left to hand off gaplessly.
    let routedFallback = null;
    const smartPairBlockReason = smartPairPlanningBlockReason({
      albumSequential,
      analysis: ctx.crossfadeAnalysis.value,
      currentTrack: ctx.activeTrack.value,
      duration: mediaDuration,
      nextAnalysis: ctx.nextCrossfadeAnalysis.value,
      nextTrack: next
    });
    if (!smartPairBlockReason && !options.force && ctx.crossfadeMode.value === 'smart' &&
        ctx.isPlaying.value && !ctx.isSeeking.value && !ctx.autoCrossfade.isActive()) {
      const routed = await maybeRunWsolaTransition({
        next,
        fromAudio,
        toAudio,
        playbackTime: Number.isFinite(mediaCurrentTime) ? mediaCurrentTime : ctx.currentTime.value,
        mediaDuration
      });
      if (routed.status === 'started') return true;
      if (routed.status === 'hold') return false;
      routedFallback = routed.fallback;
    }

    const forceFadeSeconds = options.reason === 'ended-handoff'
      ? 0.05
      : Math.min(1, ctx.crossfadeSeconds.value || 1);
    const transition = options.force
      ? { shouldStart: true, fadeSeconds: forceFadeSeconds, reason: options.reason || 'forced-handoff' }
      : routedFallback || ctx.autoCrossfade.transitionPlan({
        albumSequential,
        currentAudio: fromAudio,
        currentTime: Number.isFinite(mediaCurrentTime) ? mediaCurrentTime : ctx.currentTime.value,
        currentTrack: ctx.activeTrack.value,
        duration: mediaDuration,
        nextTrack: next,
        analysis: ctx.crossfadeAnalysis.value,
        nextAnalysis: ctx.nextCrossfadeAnalysis.value
      });
    const showSmartMix = ctx.crossfadeMode.value === 'smart' &&
      !options.force &&
      transition.transitionStyle !== 'gapless' &&
      !['normal_boundary', 'silence_trim'].includes(transition.transitionStyle);
    const secondsUntilStart = Number(transition.transitionStart) -
      (Number.isFinite(mediaCurrentTime) ? mediaCurrentTime : ctx.currentTime.value);
    if (!transition.shouldStart && showSmartMix && secondsUntilStart > 0 && secondsUntilStart <= 8) {
      ctx.prepareSmartCrossfadeMix({
        fromTrack: ctx.activeTrack.value,
        toTrack: next,
        transition,
        analysis: ctx.crossfadeAnalysis.value,
        nextAnalysis: ctx.nextCrossfadeAnalysis.value
      }, secondsUntilStart);
    } else if (!transition.shouldStart && (secondsUntilStart > 8 || !showSmartMix)) {
      ctx.clearPreparingSmartCrossfadeMix();
    }
    if (!transition.shouldStart || (!options.force && !ctx.isPlaying.value) || ctx.isSeeking.value || ctx.autoCrossfade.isActive()) {
      return false;
    }

    if (!ctx.preloadedTrackMatches(next) || !toAudio.src) {
      const didPreload = await ctx.preloadNextTrack({ force: true });
      if (!didPreload || !ctx.preloadedTrackMatches(next) || !toAudio.src) {
        return false;
      }
    }

    const resolved = ctx.nextTrackPreload.value?.resolved;
    if (!resolved) {
      return false;
    }

    const previousTrack = ctx.activeTrack.value;
    const nextTrack = ctx.activeTrackFromResolved(next, resolved);
    const nextDeck = ctx.activeAudioDeck.value === 'main' ? 'next' : 'main';
    if (showSmartMix) {
      ctx.showSmartCrossfadeMix({
        fromTrack: previousTrack,
        toTrack: nextTrack,
        transition,
        analysis: ctx.crossfadeAnalysis.value,
        nextAnalysis: ctx.nextCrossfadeAnalysis.value
      });
    }

    const didCrossfade = await ctx.autoCrossfade.start({
      fromAudio,
      toAudio,
      transition,
      volume: ctx.volume.value,
      onPromote: () => {
        const nextQueue = queueAfterTransitionPromotion(ctx.queue.value, next.id);
        ctx.finishYouTubeHistory?.();
        ctx.markPlaylistTrackPlayed?.(previousTrack);
        if (previousTrack?.id) {
          ctx.history.value.unshift(previousTrack);
          ctx.history.value = ctx.history.value.slice(0, 30);
        }

        ctx.nextPreloadRequest += 1;
        ctx.nextTrackPreload.value = null;
        ctx.activeAudioDeck.value = nextDeck;
        ctx.activeTrack.value = nextTrack;
        retireOutgoingVideo(nextTrack, fromVideo);
        ctx.startYouTubeHistory?.(nextTrack.youtubeVideoId || nextTrack.id);
        ctx.promoteCrossfadeAnalysis(nextTrack.id);
        if (ctx.crossfadeAnalysis.value.status !== 'ready') {
          void ctx.analyzeCurrentCrossfadeTrack(nextTrack, resolved.streamUrl, nextTrack.durationSeconds || 0);
        }
        ctx.queue.value = nextQueue;
        if (ctx.shuffleEnabled.value && ctx.shuffleSourceQueue.value.length) {
          ctx.shuffleSourceQueue.value = ctx.shuffleSourceQueue.value.filter((track) => track.id !== nextTrack.id);
        }
        void ctx.refillPlaylistQueue?.();
        ctx.currentTime.value = toAudio.currentTime || 0;
        ctx.seekPosition.value = ctx.currentTime.value;
        ctx.duration.value = reliablePlaybackDuration(ctx, toAudio, nextTrack);
        ctx.buffering.value = false;
        ctx.isPlaying.value = true;
        ctx.recordSessionEvent?.('crossfade', nextTrack, {
          fromTrack: previousTrack,
          queue: nextQueue,
          transitionMode: ctx.crossfadeMode.value,
          transitionReason: transition.reason || '',
          transitionStyle: transition.transitionStyle || '',
          progressSeconds: ctx.currentTime.value,
          durationSeconds: ctx.duration.value
        });
      },
      onComplete: () => {
        if (showSmartMix) ctx.completeSmartCrossfadeMix();
        ctx.clearAudioElement(fromAudio);
        // The companion audio stream is what fromAudio refers to for a music
        // video; the picture is a second element and has to be released too.
        if (fromVideo && fromVideo !== fromAudio) ctx.clearMediaElement(fromVideo);
        void ctx.preloadNextTrack();
      },
      onError: (error) => {
        if (showSmartMix) ctx.dismissSmartCrossfadeMix();
        ctx.playbackError.value = error.message;
      },
      onMixState: showSmartMix ? ctx.updateSmartCrossfadeMix : undefined
    });

    if (!didCrossfade && showSmartMix) ctx.dismissSmartCrossfadeMix();
    return didCrossfade;
  };

  ctx.finishAudioTrack = async function finishAudioTrack() {
    if (ctx.listeningParty?.value?.status === 'connected' && !ctx.listeningPartyIsHost?.value) {
      return;
    }
    const didHandoff = await ctx.maybeStartAutoCrossfade({ force: true, reason: 'ended-handoff' });
    if (didHandoff) return;

    ctx.playNext({ fromEnded: true });
  };

  ctx.playPrevious = function playPrevious(options = {}) {
    if (ctx.activePlaybackTarget?.value && ctx.activePlaybackTarget.value !== 'local') {
      ctx.sendConnectTargetCommand?.({ type: 'previous' });
      return;
    }
    if (!options.fromListeningPartyRequest && ctx.requestListeningPartyHostControl?.({ action: 'previous' })) return;
    ctx.cancelActiveCrossfade('play-previous');
    const playlistContext = ctx.playbackPlaylistContext.value;
    if (playlistContext && !ctx.shuffleEnabled.value && !playlistContext.shuffled) {
      const { activeIndex, previousTrack } = playlistPreviousState(playlistContext.allTracks, ctx.activeTrack.value?.id);
      if (activeIndex === 0) {
        ctx.seek(0);
        return;
      }
      if (previousTrack) {
        if (ctx.history.value[0]?.id === previousTrack.id) ctx.history.value.shift();
        if (ctx.activeTrack.value) {
          ctx.queue.value = [ctx.activeTrack.value, ...ctx.queue.value.filter((track) => track.id !== ctx.activeTrack.value.id)];
        }
        ctx.playTrack(previousTrack, { skipHistory: true, preserveQueue: true, sessionAction: 'previous' });
        return;
      }
    }
    const previous = ctx.history.value.shift();
    if (!previous) {
      if (ctx.activeTrack.value) ctx.seek(0);
      return;
    }

    // Replaced rather than unshifted: this is the queue's only in-place
    // mutation, and leaving it here would force the persistence watcher to
    // deep-watch a list that now runs to thousands of tracks.
    if (ctx.activeTrack.value) ctx.queue.value = [ctx.activeTrack.value, ...ctx.queue.value];
    ctx.playTrack(previous, { skipHistory: true, preserveQueue: true, sessionAction: 'previous' });
  };

  ctx.toggleShuffle = function toggleShuffle(options = {}) {
    if (ctx.activePlaybackTarget?.value && ctx.activePlaybackTarget.value !== 'local') {
      ctx.sendConnectTargetCommand?.({ type: 'toggle-shuffle' });
      return;
    }
    if (!options.fromListeningPartyRequest && ctx.requestListeningPartyHostControl?.({ action: 'toggle-shuffle' })) return;
    const playlistContext = ctx.playbackPlaylistContext.value;
    if (ctx.shuffleEnabled.value) {
      ctx.shuffleEnabled.value = false;
      ctx.stopPlaylistBackfill?.();
      if (playlistContext) playlistContext.shuffled = false;
      if (ctx.shuffleSourceQueue.value.length) {
        ctx.queue.value = ctx.shuffleSourceQueue.value.filter(ctx.isPlayableTrack);
      }
      ctx.shuffleSourceQueue.value = [];
    } else {
      ctx.shuffleSourceQueue.value = ctx.queue.value.filter(ctx.isPlayableTrack);
      ctx.queue.value = ctx.shuffleItems(ctx.shuffleSourceQueue.value);
      ctx.shuffleEnabled.value = true;
      // Shuffle turned on part way through a playlist should still reach the
      // whole playlist, not just the pages already pulled in.
      if (playlistContext) {
        playlistContext.shuffled = true;
        void ctx.backfillPlaylistQueue?.();
      }
    }

    ctx.clearNextPreload();
  };

  ctx.cycleRepeatMode = function cycleRepeatMode(options = {}) {
    if (ctx.activePlaybackTarget?.value && ctx.activePlaybackTarget.value !== 'local') {
      ctx.sendConnectTargetCommand?.({ type: 'cycle-repeat' });
      return;
    }
    if (!options.fromListeningPartyRequest && ctx.requestListeningPartyHostControl?.({ action: 'cycle-repeat' })) return;
    const order = ['off', 'queue', 'one'];
    const nextIndex = (order.indexOf(ctx.repeatMode.value) + 1) % order.length;
    ctx.repeatMode.value = order[nextIndex] || 'off';
  };

  ctx.repeatModeTitle = function repeatModeTitle() {
    if (ctx.repeatMode.value === 'one') return 'Repeat one';
    if (ctx.repeatMode.value === 'queue') return 'Repeat queue';
    return 'Repeat off';
  };

  ctx.seek = function seek(value) {
    if (ctx.activePlaybackTarget?.value && ctx.activePlaybackTarget.value !== 'local') {
      const target = Math.max(0, Math.min(Number(value) || 0, ctx.duration.value || 0));
      ctx.currentTime.value = target;
      ctx.seekPosition.value = target;
      ctx.sendConnectTargetCommand?.({ type: 'seek', value: target });
      return;
    }
    if (ctx.listeningParty.value?.status === 'connected' && !ctx.listeningPartyIsHost.value && !ctx.applyingListeningPartyState) {
      ctx.sendListeningPartyRequest({ action: 'seek', currentTime: Number(value) || 0 });
      return;
    }
    ctx.cancelActiveCrossfade('seek');
    const media = ctx.currentPlaybackElement();
    if (!media || !ctx.duration.value || ctx.activeTrackIsLive.value) return;
    const target = Math.max(0, Math.min(Number(value) || 0, ctx.duration.value));
    ctx.currentTime.value = target;
    ctx.seekPosition.value = target;

    if (typeof media.fastSeek === 'function') media.fastSeek(target);
    else media.currentTime = target;
    ctx.syncVideoCompanionAudio(target);

    ctx.queueDiscordPresenceSync();
  };

  ctx.seekToLyric = function seekToLyric(item) {
    if (!item?.canSeek) return;
    ctx.seek(ctx.playbackTimeForLyricTime(item.seekTime));
  };

  ctx.lyricAutoScrollPaused = function lyricAutoScrollPaused() {
    return Date.now() < ctx.lyricAutoScrollPausedUntil;
  };

  ctx.pauseLyricAutoScroll = function pauseLyricAutoScroll(duration = LYRIC_AUTO_SCROLL_RESUME_DELAY_MS) {
    ctx.lyricAutoScrollPausedUntil = Date.now() + duration;
    window.clearTimeout(ctx.lyricAutoScrollPauseTimer);
    ctx.lyricAutoScrollPauseTimer = window.setTimeout(() => {
      ctx.lyricAutoScrollPauseTimer = 0;
      ctx.lyricAutoScrollPausedUntil = 0;
      if (ctx.activeLyricKey.value) void ctx.scrollActiveLyric({ force: true });
    }, duration);
  };

  ctx.onLyricsUserScrollStart = function onLyricsUserScrollStart() {
    ctx.pauseLyricAutoScroll();
  };

  ctx.onLyricsUserScroll = function onLyricsUserScroll() {
    if (ctx.lyricAutoScrollPaused()) ctx.pauseLyricAutoScroll();
  };

  ctx.onLyricsPointerdown = function onLyricsPointerdown(event) {
    if (event.target === event.currentTarget) ctx.pauseLyricAutoScroll();
  };

  ctx.onSeekPositionChange = function onSeekPositionChange(value) {
    if (!ctx.isSeeking.value) ctx.seek(value);
  };

  ctx.onSeekPan = function onSeekPan(phase) {
    if (phase === 'start') {
      ctx.isSeeking.value = true;
      ctx.seekPosition.value = ctx.currentTime.value;
      return;
    }

    if (phase === 'end') {
      ctx.seek(ctx.seekPosition.value);
      ctx.isSeeking.value = false;
    }
  };

  ctx.scrollActiveLyric = async function scrollActiveLyric({ force = false } = {}) {
    if (!ctx.activeLyricKey.value) return;
    if (!force && ctx.lyricAutoScrollPaused()) return;

    await nextTick();
    const lyricRoot = ctx.fullscreenPlayerOpen.value
      ? document.querySelector('.fullscreen-player__lyrics-scroll')
      : document;
    const activeLine = lyricRoot?.querySelector('.lyrics-pause--active, .lyrics-line--active');
    activeLine?.scrollIntoView?.({
      block: 'center',
      behavior: 'smooth'
    });
  };
}
