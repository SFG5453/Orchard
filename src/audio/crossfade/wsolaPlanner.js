// Plans a beat-matched WSOLA transition: the outgoing track fades out *through*
// the incoming track's intro, reaching silence exactly as the incoming drops.
//
// The intro is the runway. It is the part of a track written to have something
// else over it, so it is the part the outgoing track fades across -- and by the
// time the incoming arrangement and vocal arrive, the outgoing one is gone.
//
// The alternative, which this replaced, held the outgoing at full level under
// the intro and then faded it *after* the drop. That fails twice over: the
// outgoing sounds untouched for the whole run-up, so the mix seems to wait for
// it to end and then rush, and the fade itself lands on top of the incoming
// track's full arrangement, which is two records at once rather than one
// becoming the other.
//
// Planning is pure and cheap so it can run on every playback tick. The heavy
// work — decoding PCM and rendering the overlap in the native addon — belongs
// to the session controller, which calls this first to learn whether a pairing
// is even worth preparing.

import {
  alignTempoOctave,
  assessTransitionTier,
  isVocalClash,
  rankMixInCandidates,
  resolveMixOutAnchor,
  vocalActivityBetween
} from './transitionPolicy.js';

export { alignTempoOctave };

// The fade is bounded in beats because overlap length is musical: bounding it
// in seconds makes a faster track get a longer mix, which is backwards. Four
// bars is the ceiling and one bar the floor, the latter for tracks whose intro
// cannot cover more.
const MIN_FADE_BEATS = 4;
const MAX_FADE_BEATS = 16;

// A ceiling on the whole overlap regardless of how long the incoming intro is.
// Keep this in step with the live smart-transition planner so enabling audio
// processing does not unexpectedly change how long the same pairing blends.
const MAX_OVERLAP_SECONDS = 16;

// One continuous equal-power fade across the whole overlap; see `handoff` and
// `bed` in native/transition/transition_render.h, where 0.5/0.5 is documented
// as the plain symmetric crossfade.
//
// The overlap used to be a bed plus a tail: the incoming intro rose to -8 dB
// while the outgoing gave up 0.7 dB, and the outgoing then did its entire
// audible fade *after* the drop. Two things were wrong with that. For the
// whole bed the outgoing sounded untouched, so the mix seemed to wait for it
// to finish and then rush; and the fade landed on top of the incoming track's
// full arrangement, which is two records at once rather than one becoming the
// other. Fading continuously through the intro instead means the outgoing is
// already gone when the drop lands.
const HANDOFF_FRACTION = 0.5;
const BED_POSITION = 0.5;

// The low end still hands over late -- see `bass_swap` in the renderer -- but
// it is now a fraction of a fade that ends at the drop rather than one that
// starts there.
const BASS_SWAP_FRACTION = 0.7;

// Extra PCM around each slice so the WSOLA similarity search and filter
// warm-up never run against a hard buffer edge.
const SLICE_PADDING_SECONDS = 1.5;

// The outgoing track must have at least this much audio before the overlap,
// and the incoming at least this much after it, so a transition never lands
// immediately after a track starts or runs into an unresolved tail.
const MIN_CLEARANCE_SECONDS = 5;

function finiteOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function refuse(reason) {
  return { ok: false, reason };
}

function nearestAtOrBefore(values, target) {
  const candidates = (Array.isArray(values) ? values : [])
    .map(Number)
    .filter((value) => Number.isFinite(value) && value >= 0 && value <= target);
  return candidates.length ? Math.max(...candidates) : null;
}

function nearest(values, target, tolerance) {
  const candidates = (Array.isArray(values) ? values : [])
    .map(Number)
    .filter((value) => Number.isFinite(value) && value >= 0 && Math.abs(value - target) <= tolerance);
  if (!candidates.length) return null;
  return candidates.reduce((best, value) =>
    Math.abs(value - target) < Math.abs(best - target) ? value : best
  );
}

// Where the incoming track takes over: the best-ranked mix-in candidate,
// snapped to a downbeat so the shared grid starts on a bar. Candidate choice
// is a ranking problem -- analyzer score, candidate type, downbeat alignment,
// available run-up and how vocal it is -- not a type lookup. This is the
// end of the fade, not the point where the incoming track starts making sound;
// it begins its intro a whole fade earlier, under the departing track.
export function incomingMixInPoint(analysis = {}) {
  const beatSeconds = finiteOrZero(analysis.beatInterval) ||
    (finiteOrZero(analysis.bpm) > 0 ? 60 / analysis.bpm : 0);
  const tolerance = Math.max(0.5, beatSeconds * 2);
  const ranked = rankMixInCandidates(analysis);
  const target = [ranked[0]?.time, analysis.mixInTime]
    .map(Number)
    .find((value) => Number.isFinite(value) && value > 0);
  if (!Number.isFinite(target)) return null;
  return nearest(analysis.downbeats, target, tolerance) ?? target;
}

// Where the incoming track first makes sound. The fade starts here at the
// earliest; anything before it is lead-in silence that would blend as a gap.
export function incomingAudibleStart(analysis = {}) {
  const candidates = [analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat]
    .map(Number)
    .filter((value) => Number.isFinite(value) && value >= 0);
  return candidates.length ? Math.min(...candidates) : 0;
}

/**
 * Plans one beat-matched transition. Returns `{ ok: false, reason }` when the
 * pairing cannot be rendered transparently, in which case the caller should
 * use the ordinary crossfade; a refusal here is a routing decision, not an
 * error. All times are seconds on each track's own media timeline.
 */
export function planWsolaTransition({
  analysis = {},
  nextAnalysis = {},
  duration = 0,
  nextDuration = 0
} = {}) {
  // The confidence gate: beat-matching is the top policy tier, and only a
  // trusted beat grid on both sides may authorize it. A refusal here routes
  // the pairing to the legacy planner, which degrades further on its own.
  const policy = assessTransitionTier({ analysis, nextAnalysis });
  if (policy.tier !== 'beatmatched') return refuse(policy.reasons[0] || 'policy');

  const outgoingBpm = finiteOrZero(analysis.bpm);
  const incomingBpm = alignTempoOctave(outgoingBpm, finiteOrZero(nextAnalysis.bpm));
  const stretchRatio = outgoingBpm / incomingBpm;

  const outgoingLength = Math.max(finiteOrZero(duration), finiteOrZero(analysis.duration));
  const incomingLength = Math.max(finiteOrZero(nextDuration), finiteOrZero(nextAnalysis.duration));
  if (outgoingLength <= 0 || incomingLength <= 0) return refuse('missing-duration');

  const incomingBeatSeconds = 60 / incomingBpm;
  const outgoingBeatSeconds = 60 / outgoingBpm;

  // The handoff: where the incoming track's arrangement and vocal arrive.
  const incomingDropTime = incomingMixInPoint(nextAnalysis);
  if (!Number.isFinite(incomingDropTime) || incomingDropTime < 0) return refuse('incoming-mix-in');

  // Where the overlap ends: the best-ranked mix-out anchor that does not skip
  // more of the outgoing track's music than the policy budget allows. Resolved
  // early so the vocal-activity windows below can be measured against it.
  const contentEnd = finiteOrZero(analysis.contentEndTime) || outgoingLength;
  const mixOutAnchor = resolveMixOutAnchor(analysis, { contentEnd, duration: outgoingLength });
  const overlapEndTarget = Math.min(outgoingLength, mixOutAnchor.time);

  // The fade runs *through* the incoming track's intro and ends on its drop,
  // so it can be at most as long as that intro. Quantized down to whole bars
  // of the shared grid so both ends stay on a downbeat.
  const audibleStart = incomingAudibleStart(nextAnalysis);
  const availableFadeBeats = Math.max(0, incomingDropTime - audibleStart) / incomingBeatSeconds;
  const cappedByOverlap = Math.floor(Math.floor(MAX_OVERLAP_SECONDS / incomingBeatSeconds) / 4) * 4;
  if (cappedByOverlap < MIN_FADE_BEATS) return refuse('overlap-too-long');
  let fadeBeats = Math.min(
    MAX_FADE_BEATS,
    cappedByOverlap,
    Math.floor(availableFadeBeats / 4) * 4
  );
  // A track that starts singing immediately cannot hide a four-bar fade. Rather
  // than refuse the pairing outright -- which is what made this shape fail the
  // first time it was tried -- the fade shortens to whatever the intro covers,
  // down to a one-bar floor.
  if (fadeBeats < MIN_FADE_BEATS) fadeBeats = MIN_FADE_BEATS;

  // Both sides singing through the fade is the case this shape exists to avoid,
  // and when it happens anyway the shortest fade is the least bad one.
  const fadeVocalClash = isVocalClash(
    vocalActivityBetween(
      analysis,
      overlapEndTarget - fadeBeats * outgoingBeatSeconds,
      overlapEndTarget
    ),
    vocalActivityBetween(
      nextAnalysis,
      Math.max(audibleStart, incomingDropTime - fadeBeats * incomingBeatSeconds),
      incomingDropTime
    )
  );
  if (fadeVocalClash) fadeBeats = MIN_FADE_BEATS;

  const overlapBeats = fadeBeats;
  // The same beat count on both grids: the outgoing side is consumed at its
  // own tempo and stretched onto the incoming grid by the renderer.
  const outgoingOverlapSeconds = overlapBeats * outgoingBeatSeconds;
  const overlapSeconds = overlapBeats * incomingBeatSeconds;

  // The overlap ends on the drop, so the incoming track enters a whole fade
  // ahead of it. Clamped at its audible start so the mix never opens on
  // lead-in silence; whatever the clamp takes away shortens the fade rather
  // than moving the drop, which must stay where the analyzer found it.
  const incomingCueTime = Math.max(audibleStart, incomingDropTime - overlapSeconds);
  const fadeSeconds = incomingDropTime - incomingCueTime;
  if (fadeSeconds <= 0) return refuse('incoming-no-intro');

  const startTarget = overlapEndTarget - outgoingOverlapSeconds;
  const transitionStart = nearestAtOrBefore(analysis.downbeats, startTarget) ?? startTarget;
  if (transitionStart < MIN_CLEARANCE_SECONDS) return refuse('outgoing-too-short');
  const transitionEnd = transitionStart + outgoingOverlapSeconds;
  if (transitionEnd > outgoingLength + 0.05) return refuse('outgoing-overlap-overruns');

  const incomingResumeTime = incomingCueTime + overlapSeconds;
  if (incomingResumeTime + MIN_CLEARANCE_SECONDS > incomingLength) return refuse('incoming-too-short');

  const outgoingSliceStart = Math.max(0, transitionStart - SLICE_PADDING_SECONDS);
  const incomingSliceStart = Math.max(0, incomingCueTime - SLICE_PADDING_SECONDS);
  return {
    ok: true,
    tier: policy.tier,
    beatConfidence: policy.beatConfidence,
    mixOutType: mixOutAnchor.type,
    vocalClash: fadeVocalClash,
    transitionStart,
    transitionEnd,
    overlapSeconds,
    beats: overlapBeats,
    fadeBeats,
    handoffFraction: HANDOFF_FRACTION,
    bedPosition: BED_POSITION,
    bassSwapFraction: BASS_SWAP_FRACTION,
    outgoingBpm,
    incomingBpm,
    stretchRatio,
    incomingCueTime,
    incomingDropTime,
    incomingResumeTime,
    outgoingSlice: {
      start: outgoingSliceStart,
      end: Math.min(outgoingLength, transitionEnd + SLICE_PADDING_SECONDS),
      anchor: transitionStart - outgoingSliceStart
    },
    incomingSlice: {
      start: incomingSliceStart,
      end: Math.min(incomingLength, incomingResumeTime + SLICE_PADDING_SECONDS),
      anchor: incomingCueTime - incomingSliceStart
    }
  };
}
