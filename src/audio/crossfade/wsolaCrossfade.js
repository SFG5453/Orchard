// Runs a beat-matched transition from a pre-rendered overlap buffer.
//
// The overlap itself — time-stretch, beat alignment, bass handover — was mixed
// offline by the native renderer into one finished stereo buffer, so playback
// here is a scheduling problem, not a DSP problem: play the buffer on the
// sample-accurate context clock while both media elements run muted, then hand
// audible playback to the incoming element the instant the buffer ends. The
// elements keep playing silently underneath so a cancel at any point can
// restore ordinary playback without re-buffering.
import { planWsolaTransition } from './wsolaPlanner.js';

// How far ahead of the transition preparation may begin. Decoding two tracks
// and rendering the overlap takes a few seconds; starting ~30s out leaves
// slack for slow networks without holding PCM in memory for whole tracks.
export const WSOLA_PREPARE_LEAD_SECONDS = 30;

// How far ahead of the transition start() wants to be called. Scheduling needs
// a moment of runway on the context clock; the playback clock ticks every
// 120ms, so a lead over ~0.5s guarantees at least one tick lands in time.
export const WSOLA_START_LEAD_SECONDS = 1.2;

// Starting later than this after the planned downbeat would audibly clip the
// front of the overlap; past it the caller should use the ordinary crossfade.
const MAX_LATE_START_SECONDS = 0.35;

// The incoming element runs muted during the overlap; corrections are
// inaudible while muted, but each one is a seek the element needs time to
// settle after, so they are kept well clear of the handoff.
const DRIFT_TOLERANCE_SECONDS = 0.04;

// Length of the fades that hand audio between the rendered buffer and a media
// element, at each end of the overlap.
//
// Deliberately tiny. Both sides carry the same audio at the handoff, but a
// media element's position is only accurate to tens of milliseconds, so any
// window where both are audible is two near-copies of the same signal offset
// in time -- comb filtering, heard as a metallic rasp over the transition.
// Ten milliseconds is long enough to avoid a click and short enough that the
// residual is a single transient rather than an audible effect.
const HANDOFF_FADE_SECONDS = 0.01;

/**
 * The rendered overlap contains raw decoded PCM, so it can only replace the
 * live decks when their per-source processing is effectively flat. The legacy
 * crossfade keeps both media elements in their normal graphs and is therefore
 * the safe fallback whenever normalization, EQ, balance, preamp, or track gain
 * would make the rendered audio sound different.
 */
export function wsolaProcessingCompatible({
  normalizationEnabled = false,
  audioEngineConfig = {},
  outgoingGainDb = 0,
  incomingGainDb = 0
} = {}) {
  if (normalizationEnabled) return false;
  if (!audioEngineConfig?.enabled) return true;
  // Preamp is only active as part of the manual-EQ branch.
  if (audioEngineConfig.eqEnabled || audioEngineConfig.autoEqEnabled) return false;
  if (Math.abs(Number(audioEngineConfig.balance) || 0) > 0.001) return false;
  return Math.abs(Number(outgoingGainDb) || 0) <= 0.001 &&
    Math.abs(Number(incomingGainDb) || 0) <= 0.001;
}

function pairKey(fromTrackId, toTrackId) {
  return `${String(fromTrackId || '')}>${String(toTrackId || '')}`;
}

function sliceChannels(buffer, startSeconds, endSeconds) {
  const sampleRate = buffer.sampleRate;
  const start = Math.max(0, Math.floor(startSeconds * sampleRate));
  const end = Math.min(buffer.length, Math.ceil(endSeconds * sampleRate));
  if (end <= start) return [];
  const channels = [];
  for (let index = 0; index < buffer.numberOfChannels; index += 1) {
    channels.push(buffer.getChannelData(index).slice(start, end));
  }
  // Mono tracks are duplicated so both sources always present the same
  // channel count to the renderer, which refuses mismatched layouts.
  if (channels.length === 1) channels.push(new Float32Array(channels[0]));
  return channels.slice(0, 2);
}

export function createWsolaCrossfade({
  analyzer,
  bridge = globalThis.orchardAudioAnalysis,
  report = () => {}
} = {}) {
  // One preparation at a time: transitions are strictly sequential, so a new
  // pairing always supersedes whatever was prepared before it.
  let preparation = null;
  let session = null;
  let sequence = 0;
  let targetVolume = 1;

  function plan(options) {
    return planWsolaTransition(options);
  }

  function preparationStatus(fromTrackId, toTrackId) {
    if (!preparation || preparation.key !== pairKey(fromTrackId, toTrackId)) return 'idle';
    return preparation.status;
  }

  // Returns the plan and render together: the buffer was mixed against the
  // plan captured at prepare time, and starting against a fresher plan (for
  // example after BPM metadata enrichment shifted the analysis) would place
  // the rendered downbeats at the wrong media positions.
  function preparedTransition(fromTrackId, toTrackId) {
    if (preparationStatus(fromTrackId, toTrackId) !== 'ready') return null;
    return { plan: preparation.plan, render: preparation.render };
  }

  function prepare({ fromTrackId, toTrackId, fromUrl, toUrl, plan: transitionPlan }) {
    const key = pairKey(fromTrackId, toTrackId);
    if (preparation?.key === key && preparation.status !== 'failed') return preparation.promise;
    if (!transitionPlan?.ok || !fromUrl || !toUrl || typeof bridge?.renderTransition !== 'function') {
      preparation = { key, status: 'failed', reason: 'unavailable', promise: Promise.resolve(null) };
      return preparation.promise;
    }

    const entry = { key, status: 'pending', plan: transitionPlan, render: null, reason: '' };
    report('wsola-prepare-start', {
      trackId: String(toTrackId),
      transitionStart: transitionPlan.transitionStart,
      overlapSeconds: transitionPlan.overlapSeconds
    });
    entry.promise = (async () => {
      const startedAt = Date.now();
      const [fromBuffer, toBuffer] = await Promise.all([
        analyzer.decodeAudio(fromUrl),
        analyzer.decodeAudio(toUrl)
      ]);
      if (!fromBuffer || !toBuffer) throw new Error('Transition PCM decoding returned no audio');
      const sampleRate = fromBuffer.sampleRate;
      const outgoing = {
        channels: sliceChannels(fromBuffer, transitionPlan.outgoingSlice.start, transitionPlan.outgoingSlice.end),
        anchor: transitionPlan.outgoingSlice.anchor,
        bpm: transitionPlan.outgoingBpm
      };
      const incoming = {
        channels: sliceChannels(toBuffer, transitionPlan.incomingSlice.start, transitionPlan.incomingSlice.end),
        anchor: transitionPlan.incomingSlice.anchor,
        bpm: transitionPlan.incomingBpm
      };
      const result = await bridge.renderTransition(outgoing, incoming, {
        sampleRate,
        beats: transitionPlan.beats,
        bassSwap: transitionPlan.bassSwapFraction,
        handoff: transitionPlan.handoffFraction,
        bed: transitionPlan.bedPosition
      });
      if (!result?.rendered) {
        entry.status = 'failed';
        entry.reason = String(result?.rejected || 'render-refused');
        report('wsola-prepare-refused', { trackId: String(toTrackId), reason: entry.reason });
        return null;
      }
      entry.render = {
        channels: result.channels,
        sampleRate: result.sampleRate,
        stretchRatio: result.stretchRatio
      };
      entry.status = 'ready';
      report('wsola-prepare-ready', {
        trackId: String(toTrackId),
        elapsedMs: Date.now() - startedAt,
        stretchRatio: result.stretchRatio,
        overlapSeconds: result.channels?.[0]?.length / result.sampleRate || 0
      });
      return entry.render;
    })().catch((error) => {
      entry.status = 'failed';
      entry.reason = String(error?.message || error || 'prepare-failed');
      report('wsola-prepare-failed', { trackId: String(toTrackId), errorMessage: entry.reason });
      return null;
    });
    preparation = entry;
    return entry.promise;
  }

  function isActive() {
    return Boolean(session);
  }

  function setTargetVolume(value) {
    const clamped = Math.max(0, Math.min(1, Number(value) || 0));
    targetVolume = clamped;
    if (!session) return;
    session.handle?.setVolume(clamped);
    // Element audibility lives on the normalized mix envelope, so both master
    // gains can follow the slider without unmuting either shadow deck.
    analyzer.setVolume(session.fromAudio, clamped);
    analyzer.setVolume(session.toAudio, clamped);
  }

  function clearTimers(state) {
    state.timers.forEach((timer) => window.clearTimeout(timer));
    state.timers.length = 0;
  }

  // `reason` names the caller, so a session that ends early can be told apart
  // from an ordinary teardown without reproducing it under a debugger. A
  // healthy transition never arrives here at all -- it reports wsola-complete.
  function cancel(reason = 'unspecified') {
    const state = session;
    if (!state) return;
    session = null;
    sequence += 1;
    clearTimers(state);
    state.handle?.stop();
    // Settle the pending start() so its caller never hangs on a cancelled
    // completion timer; the sequence bump makes the resolution a no-op there.
    state.finish?.();

    const elapsed = Math.max(0, analyzer.currentTime() - state.overlapStartTime);
    analyzer.resetMixElement?.(state.fromAudio);
    analyzer.resetMixElement?.(state.toAudio);
    if (state.promoted) {
      // The incoming element is already the active deck; it has been playing
      // muted in position, so restoring volume is the whole recovery.
      analyzer.setVolume(state.toAudio, targetVolume);
      state.fromAudio.pause();
      analyzer.setVolume(state.fromAudio, 0);
    } else {
      // Pre-promote the outgoing element is still authoritative. The buffer
      // consumed its media at the stretch ratio while the element ran at unit
      // rate, so realign before unmuting.
      const ratio = Math.max(0.0001, Number(state.render.stretchRatio) || 1);
      const expected = state.plan.transitionStart + elapsed / ratio;
      if (Math.abs(state.fromAudio.currentTime - expected) > DRIFT_TOLERANCE_SECONDS) {
        try {
          state.fromAudio.currentTime = expected;
        } catch {}
      }
      analyzer.setVolume(state.fromAudio, targetVolume);
      state.toAudio.pause();
      analyzer.setVolume(state.toAudio, 0);
    }
    report('wsola-cancelled', {
      reason: String(reason),
      promoted: state.promoted,
      elapsedSeconds: elapsed,
      // How much of the rendered overlap was thrown away. Small but non-zero
      // means the buffer was cut short of its own tail.
      remainingSeconds: Math.max(0, (Number(state.plan?.overlapSeconds) || 0) - elapsed)
    });
  }

  async function start({ fromAudio, toAudio, plan: transitionPlan, render, volume, onPromote, onComplete, onError }) {
    if (session || !fromAudio || !toAudio || !transitionPlan?.ok || !render?.channels?.length) {
      return false;
    }
    let untilStart = transitionPlan.transitionStart - fromAudio.currentTime;
    if (untilStart < -MAX_LATE_START_SECONDS) {
      report('wsola-start-too-late', { lateBySeconds: -untilStart });
      return false;
    }

    const mySequence = ++sequence;
    setTargetVolume(volume);
    try {
      await analyzer.resume?.();
      if (mySequence !== sequence) return false;

      // `resume()` is asynchronous while the media element keeps advancing.
      // Re-sample its clock so context startup latency cannot put the buffer
      // behind the live deck before the first handoff.
      untilStart = transitionPlan.transitionStart - fromAudio.currentTime;
      if (untilStart < -MAX_LATE_START_SECONDS) {
        report('wsola-start-too-late', { lateBySeconds: -untilStart });
        return false;
      }

      const now = analyzer.currentTime();
      const stretchRatio = Math.max(0.0001, Number(render.stretchRatio) || 1);
      // `untilStart` is measured on the outgoing media timeline, while the
      // buffer offset is measured on its stretched output timeline.
      const offset = Math.max(0, -untilStart) * stretchRatio;
      const when = now + Math.max(0, untilStart);
      const handle = analyzer.playPcmBuffer({
        channels: render.channels,
        sampleRate: render.sampleRate,
        when,
        offset,
        volume: targetVolume
      });
      if (!handle) throw new Error('Web Audio transition playback is unavailable');

      const state = {
        fromAudio,
        toAudio,
        plan: transitionPlan,
        render,
        handle,
        // Context time at which buffer position `offset` began: the anchor for
        // every mapping between the overlap and the media timelines.
        overlapStartTime: when - offset,
        promoted: false,
        timers: []
      };
      session = state;

      // Hand over from the outgoing element to the buffer with complementary
      // fades over the same instant, so the two never both carry the outgoing
      // track at full gain. The element keeps playing muted afterwards so a
      // cancel can restore it instantly.
      const connected = analyzer.fadeVolume(
        fromAudio, 1, 0, when, HANDOFF_FADE_SECONDS
      );
      if (!connected) throw new Error('Transition elements are outside the audio graph');
      handle.fade(0, 1, when, HANDOFF_FADE_SECONDS);

      // The incoming element shadows the buffer's incoming side, muted. It
      // only has to be exactly placed by the time the buffer ends.
      analyzer.setVolume(toAudio, targetVolume);
      if (!analyzer.setMixVolume?.(toAudio, 0)) {
        throw new Error('Transition elements are outside the audio graph');
      }
      toAudio.currentTime = transitionPlan.incomingCueTime + offset;
      await toAudio.play();
      if (mySequence !== sequence) return false;

      const schedule = (atContextTime, callback) => {
        const delayMs = Math.max(0, (atContextTime - analyzer.currentTime()) * 1000);
        state.timers.push(window.setTimeout(() => {
          if (mySequence !== sequence) return;
          callback();
        }, delayMs));
      };
      const correctDrift = () => {
        const expected = transitionPlan.incomingCueTime +
          (analyzer.currentTime() - state.overlapStartTime);
        if (Math.abs(toAudio.currentTime - expected) > DRIFT_TOLERANCE_SECONDS) {
          try {
            toAudio.currentTime = expected;
          } catch {}
        }
      };

      const swapAt = state.overlapStartTime +
        transitionPlan.overlapSeconds * transitionPlan.bassSwapFraction;
      const endAt = handle.endTime;
      // All corrections sit in the first two thirds of the overlap: each one
      // is a seek, and the element needs settled, steady playback well before
      // it becomes audible.
      schedule(state.overlapStartTime + transitionPlan.overlapSeconds * 0.15, correctDrift);
      schedule((state.overlapStartTime + swapAt) / 2, correctDrift);
      schedule(state.overlapStartTime + transitionPlan.overlapSeconds * 0.6, correctDrift);
      schedule(Math.max(now, swapAt), () => {
        if (state.promoted) return;
        state.promoted = true;
        fromAudio.pause();
        onPromote?.();
      });
      // Hand back to the element as the buffer's last samples play out, with
      // the same complementary pair of fades used at the start. Both carry the
      // incoming track here, so the exchange is level-preserving.
      handle.fade(1, 0, endAt - HANDOFF_FADE_SECONDS, HANDOFF_FADE_SECONDS);
      analyzer.fadeVolume(
        toAudio, 0, 1, endAt - HANDOFF_FADE_SECONDS, HANDOFF_FADE_SECONDS
      );

      await new Promise((resolve) => {
        state.finish = resolve;
        schedule(endAt + 0.05, resolve);
      });
      if (mySequence !== sequence) return false;

      session = null;
      clearTimers(state);
      analyzer.resetMixElement?.(toAudio);
      analyzer.resetMixElement?.(fromAudio);
      analyzer.setVolume(toAudio, targetVolume);
      analyzer.setVolume(fromAudio, 0);
      fromAudio.pause();
      report('wsola-complete', { stretchRatio: render.stretchRatio });
      onComplete?.();
      return true;
    } catch (error) {
      if (mySequence !== sequence) return false;
      const state = session;
      session = null;
      if (state) {
        clearTimers(state);
        state.handle?.stop();
      }
      analyzer.resetMixElement?.(fromAudio);
      analyzer.resetMixElement?.(toAudio);
      analyzer.setVolume(fromAudio, targetVolume);
      toAudio.pause();
      analyzer.setVolume(toAudio, 0);
      report('wsola-start-failed', { errorMessage: String(error?.message || error) });
      onError?.(error);
      return false;
    }
  }

  return {
    cancel,
    isActive,
    plan,
    prepare,
    preparationStatus,
    preparedTransition,
    setTargetVolume,
    start
  };
}
