// Plans a beat-matched WSOLA transition in the Spotify AutoMix style measured
// from reference captures: the outgoing track runs to its mix-out point while
// the incoming track's intro plays underneath it, and the two cross at equal
// power on the incoming track's drop.
//
// The pre-roll is the point of the whole shape. Entering at the drop instead
// puts the incoming vocal on top of an outgoing track that is still singing --
// on a typical pop pairing that is several seconds of two lead vocals at once,
// which reads as a mistake however well the grids line up. The intro is the
// part of a track written to sit under something else, so that is the part
// that overlaps.
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

// Beats of overlap after the handoff: the outgoing track's fade once the
// incoming has arrived. Measured from the Spotify reference capture, but
// capped in seconds (mirrors the legacy planner's tailSeconds clamp) so a
// slow track doesn't turn "the fade" into a 13-14s wait behind the new vocal.
// Two bars. Sixteen beats of fade was long enough that the mix stopped reading
// as a transition and started reading as two records playing at once -- at
// 126 BPM that is a 7.6-second fade on top of an equally long bed. The seconds
// clamp is a safety rail for extreme tempi, not the primary control: overlap
// length is musical, so it is counted in beats.
const NOMINAL_TAIL_BEATS = 8;
const TAIL_MIN_SECONDS = 2;
const TAIL_MAX_SECONDS = 4;

// The incoming track's intro plays underneath the outgoing track before the
// handoff. Quantized to bars, and bounded: too short and the drop arrives
// unannounced, too long and the outgoing track is buried under a bed for most
// of its final phrase. Four bars is the cap because a long intro is not an
// invitation to play all of it -- at 48 beats a slow track sat under the
// outgoing one for fifteen seconds before anything started to move.
const MIN_PREROLL_BEATS = 4;
const MAX_PREROLL_BEATS = 8;

// A ceiling on the whole overlap regardless of how long the incoming intro is.
// Keep this in step with the live smart-transition planner so enabling audio
// processing does not unexpectedly change how long the same pairing blends.
const MAX_OVERLAP_SECONDS = 16;

// How far along the equal-power fade the pre-roll travels; see `bed` in
// native/transition/transition_render.h. Low, so the pre-roll is the incoming
// intro rising to a bed under a still-full outgoing track, and the outgoing
// track's audible fade is the tail alone rather than the whole overlap.
const BED_POSITION = 0.25;

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
// handoff, not the point where the incoming track starts making sound; it
// begins its intro a pre-roll earlier, underneath the outgoing track.
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

// Where the incoming track first makes sound. The pre-roll starts here at the
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

  // Quantized to a whole bar so the handoff stays on the grid; floored at one
  // bar so a very fast tempo doesn't collapse the fade to nothing.
  let tailBeats = Math.max(
    4,
    Math.round(
      clamp(NOMINAL_TAIL_BEATS * incomingBeatSeconds, TAIL_MIN_SECONDS, TAIL_MAX_SECONDS) /
        incomingBeatSeconds /
        4
    ) * 4
  );

  // The handoff: where the incoming track's arrangement and vocal arrive.
  const incomingDropTime = incomingMixInPoint(nextAnalysis);
  if (!Number.isFinite(incomingDropTime) || incomingDropTime < 0) return refuse('incoming-mix-in');

  // Where the overlap ends: the best-ranked mix-out anchor that does not skip
  // more of the outgoing track's music than the policy budget allows. Resolved
  // early so the vocal-activity windows below can be measured against it.
  const contentEnd = finiteOrZero(analysis.contentEndTime) || outgoingLength;
  const mixOutAnchor = resolveMixOutAnchor(analysis, { contentEnd, duration: outgoingLength });
  const overlapEndTarget = Math.min(outgoingLength, mixOutAnchor.time);

  // The tail is the incoming vocal singing over the outgoing fade. When the
  // masks show both tracks measurably singing through it, one bar of fade is
  // all the clash that is tolerated.
  const tailVocalClash = isVocalClash(
    vocalActivityBetween(
      analysis,
      overlapEndTarget - tailBeats * outgoingBeatSeconds,
      overlapEndTarget
    ),
    vocalActivityBetween(
      nextAnalysis,
      incomingDropTime,
      incomingDropTime + tailBeats * incomingBeatSeconds
    )
  );
  if (tailVocalClash) tailBeats = 4;

  // The incoming track's intro is what plays underneath the outgoing track, so
  // the pre-roll can be at most as long as that intro. Quantized down to whole
  // bars of the shared grid, so the handoff stays on a downbeat.
  const audibleStart = incomingAudibleStart(nextAnalysis);
  const availablePrerollBeats = Math.max(0, incomingDropTime - audibleStart) / incomingBeatSeconds;
  const cappedByOverlap = Math.floor(
    (Math.floor(MAX_OVERLAP_SECONDS / incomingBeatSeconds) - tailBeats) / 4
  ) * 4;
  if (cappedByOverlap < MIN_PREROLL_BEATS) return refuse('overlap-too-long');
  let prerollBeats = Math.max(
    MIN_PREROLL_BEATS,
    Math.min(
      MAX_PREROLL_BEATS,
      cappedByOverlap,
      Math.floor(availablePrerollBeats / 4) * 4
    )
  );

  // The bed is supposed to be an instrumental intro under a track that is
  // still at full level. If both sides are singing there instead, keep the
  // bed to the one-bar minimum rather than running a long double-vocal ride.
  const bedVocalClash = isVocalClash(
    vocalActivityBetween(
      analysis,
      overlapEndTarget - (prerollBeats + tailBeats) * outgoingBeatSeconds,
      overlapEndTarget - tailBeats * outgoingBeatSeconds
    ),
    vocalActivityBetween(
      nextAnalysis,
      Math.max(audibleStart, incomingDropTime - prerollBeats * incomingBeatSeconds),
      incomingDropTime
    )
  );
  if (bedVocalClash) prerollBeats = MIN_PREROLL_BEATS;

  const overlapBeats = prerollBeats + tailBeats;
  // The same beat count on both grids: the outgoing side is consumed at its
  // own tempo and stretched onto the incoming grid by the renderer.
  const outgoingOverlapSeconds = overlapBeats * outgoingBeatSeconds;
  const overlapSeconds = overlapBeats * incomingBeatSeconds;

  // The incoming track enters a pre-roll ahead of its drop. Clamped at its
  // audible start so the mix never opens on lead-in silence.
  const incomingCueTime = Math.max(
    audibleStart,
    incomingDropTime - prerollBeats * incomingBeatSeconds
  );
  // Whatever the clamp took away shortens the pre-roll rather than shifting
  // the drop, which must stay where the analyzer found it.
  const prerollSeconds = incomingDropTime - incomingCueTime;
  if (prerollSeconds <= 0) return refuse('incoming-no-preroll');

  const startTarget = overlapEndTarget - outgoingOverlapSeconds;
  const transitionStart = nearestAtOrBefore(analysis.downbeats, startTarget) ?? startTarget;
  if (transitionStart < MIN_CLEARANCE_SECONDS) return refuse('outgoing-too-short');
  const transitionEnd = transitionStart + outgoingOverlapSeconds;
  if (transitionEnd > outgoingLength + 0.05) return refuse('outgoing-overlap-overruns');

  const incomingResumeTime = incomingCueTime + overlapSeconds;
  if (incomingResumeTime + MIN_CLEARANCE_SECONDS > incomingLength) return refuse('incoming-too-short');

  // The mix changes hands on the drop: before it the incoming track is a bed
  // under the outgoing one, after it the outgoing track fades away.
  const handoffFraction = prerollSeconds / overlapSeconds;

  // The low end changes hands on the drop too -- that is what the drop is --
  // held a bar past it so the incoming track does not arrive early.
  const swapBeat = Math.max(4, Math.min(overlapBeats - 4, prerollBeats + 4));
  const bassSwapFraction = swapBeat / overlapBeats;

  const outgoingSliceStart = Math.max(0, transitionStart - SLICE_PADDING_SECONDS);
  const incomingSliceStart = Math.max(0, incomingCueTime - SLICE_PADDING_SECONDS);
  return {
    ok: true,
    tier: policy.tier,
    beatConfidence: policy.beatConfidence,
    mixOutType: mixOutAnchor.type,
    vocalClash: { bed: bedVocalClash, tail: tailVocalClash },
    transitionStart,
    transitionEnd,
    overlapSeconds,
    beats: overlapBeats,
    prerollBeats,
    tailBeats,
    handoffFraction,
    bedPosition: BED_POSITION,
    bassSwapFraction,
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
