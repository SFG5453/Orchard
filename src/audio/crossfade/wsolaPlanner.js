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

// One octave either side of a typical dance tempo; outside this the analysis
// is treated as noise rather than a tempo.
const MIN_BPM = 40;
const MAX_BPM = 220;

// Mirrors kMaxTransparentRatioDeviation in native/transition/wsola.h. The
// native renderer re-checks this; duplicating the number here avoids shipping
// PCM across the IPC boundary for a pairing that is certain to be refused.
const MAX_STRETCH_DEVIATION = 0.08;

// Beats of overlap after the handoff: the outgoing track's fade once the
// incoming has arrived. Measured from the Spotify reference capture.
const TAIL_BEATS = 16;

// The incoming track's intro plays underneath the outgoing track before the
// handoff. Quantized to bars, and bounded: too short and the drop arrives
// unannounced, too long and the outgoing track is buried under a bed for most
// of its final phrase. Four bars is the cap because a long intro is not an
// invitation to play all of it -- at 48 beats a slow track sat under the
// outgoing one for fifteen seconds before anything started to move.
const MIN_PREROLL_BEATS = 4;
const MAX_PREROLL_BEATS = 16;

// A ceiling on the whole overlap regardless of how long the incoming intro is.
const MAX_OVERLAP_SECONDS = 20;

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

function refuse(reason) {
  return { ok: false, reason };
}

// Halves or doubles the incoming tempo until it is as close as possible to the
// outgoing, the way a DJ counts a 63 BPM track against a 126 BPM one. The
// returned tempo defines the shared grid; "beats" elsewhere are beats of that
// grid, not of the incoming track's own metadata.
export function alignTempoOctave(outgoingBpm, incomingBpm) {
  let aligned = incomingBpm;
  while (aligned / outgoingBpm > 1.5) aligned /= 2;
  while (aligned / outgoingBpm < 0.67) aligned *= 2;
  return aligned;
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

// Where the incoming track takes over: its analyzed drop or best mix-in
// candidate, snapped to a downbeat so the shared grid starts on a bar. This is
// the handoff, not the point where the incoming track starts making sound --
// it begins its intro a pre-roll earlier, underneath the outgoing track.
export function incomingMixInPoint(analysis = {}) {
  const beatSeconds = finiteOrZero(analysis.beatInterval) ||
    (finiteOrZero(analysis.bpm) > 0 ? 60 / analysis.bpm : 0);
  const tolerance = Math.max(0.5, beatSeconds * 2);
  const candidates = Array.isArray(analysis.mixInCandidates) ? analysis.mixInCandidates : [];
  const drop = candidates.find((candidate) =>
    candidate?.type === 'main_drop' || candidate?.type === 'intro_drop');
  const scored = [...candidates].sort((left, right) =>
    finiteOrZero(right?.score) - finiteOrZero(left?.score))[0];
  const target = [drop?.time, scored?.time, analysis.mixInTime]
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
  const outgoingBpm = finiteOrZero(analysis.bpm);
  const rawIncomingBpm = finiteOrZero(nextAnalysis.bpm);
  if (outgoingBpm < MIN_BPM || outgoingBpm > MAX_BPM) return refuse('outgoing-tempo');
  if (rawIncomingBpm < MIN_BPM || rawIncomingBpm > MAX_BPM) return refuse('incoming-tempo');

  const incomingBpm = alignTempoOctave(outgoingBpm, rawIncomingBpm);
  const stretchRatio = outgoingBpm / incomingBpm;
  if (Math.abs(stretchRatio - 1) > MAX_STRETCH_DEVIATION) return refuse('tempo-distance');

  const outgoingLength = Math.max(finiteOrZero(duration), finiteOrZero(analysis.duration));
  const incomingLength = Math.max(finiteOrZero(nextDuration), finiteOrZero(nextAnalysis.duration));
  if (outgoingLength <= 0 || incomingLength <= 0) return refuse('missing-duration');

  const incomingBeatSeconds = 60 / incomingBpm;
  const outgoingBeatSeconds = 60 / outgoingBpm;

  // The handoff: where the incoming track's arrangement and vocal arrive.
  const incomingDropTime = incomingMixInPoint(nextAnalysis);
  if (!Number.isFinite(incomingDropTime) || incomingDropTime < 0) return refuse('incoming-mix-in');

  // The incoming track's intro is what plays underneath the outgoing track, so
  // the pre-roll can be at most as long as that intro. Quantized down to whole
  // bars of the shared grid, so the handoff stays on a downbeat.
  const audibleStart = incomingAudibleStart(nextAnalysis);
  const availablePrerollBeats = Math.max(0, incomingDropTime - audibleStart) / incomingBeatSeconds;
  const cappedByOverlap = Math.floor(MAX_OVERLAP_SECONDS / incomingBeatSeconds) - TAIL_BEATS;
  const prerollBeats = Math.max(
    MIN_PREROLL_BEATS,
    Math.min(
      MAX_PREROLL_BEATS,
      cappedByOverlap,
      Math.floor(availablePrerollBeats / 4) * 4
    )
  );

  const overlapBeats = prerollBeats + TAIL_BEATS;
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

  // The outgoing overlap ends where its content does: an interior mix-out if
  // the analyzer found one, otherwise the end of real content.
  const contentEnd = finiteOrZero(analysis.contentEndTime) || outgoingLength;
  const mixOut = finiteOrZero(analysis.mixOutTime);
  const overlapEndTarget = Math.min(
    outgoingLength,
    mixOut > 0 && mixOut < contentEnd - 1 ? mixOut : contentEnd
  );
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
    transitionStart,
    transitionEnd,
    overlapSeconds,
    beats: overlapBeats,
    prerollBeats,
    tailBeats: TAIL_BEATS,
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
