// Plans a beat-matched WSOLA transition in the Spotify AutoMix style measured
// from reference captures: the outgoing track runs to its mix-out point, the
// incoming track enters at a mix-in point deep in the track (skipping its
// intro), and the two overlap for a fixed number of beats on a shared grid.
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

// Measured from the Spotify reference capture: a 16-beat overlap (~7.6s at
// 126 BPM). The overlap spans the same beat count on both grids because the
// outgoing track is stretched onto the incoming tempo.
const OVERLAP_BEATS = 16;

// Fraction of the overlap where the low end hands over, chosen by ear against
// rendered A/B pairs; quantized to a bar so the swap lands on a downbeat.
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

// Where the incoming track should enter: its analyzed drop or best mix-in
// candidate, snapped to a downbeat so the shared grid starts on a bar.
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

  // The same beat count on both grids: the outgoing side is consumed at its
  // own tempo and stretched onto the incoming grid by the renderer.
  const outgoingOverlapSeconds = OVERLAP_BEATS * (60 / outgoingBpm);
  const overlapSeconds = OVERLAP_BEATS * (60 / incomingBpm);

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

  const incomingCueTime = incomingMixInPoint(nextAnalysis);
  if (!Number.isFinite(incomingCueTime) || incomingCueTime < 0) return refuse('incoming-mix-in');
  const incomingResumeTime = incomingCueTime + overlapSeconds;
  if (incomingResumeTime + MIN_CLEARANCE_SECONDS > incomingLength) return refuse('incoming-too-short');

  // Swap the bass on a bar boundary of the shared grid, staying at least one
  // bar clear of each edge so the handover is audible as an event.
  const swapBeat = Math.max(4, Math.min(OVERLAP_BEATS - 4,
    Math.round((BASS_SWAP_FRACTION * OVERLAP_BEATS) / 4) * 4));
  const bassSwapFraction = swapBeat / OVERLAP_BEATS;

  const outgoingSliceStart = Math.max(0, transitionStart - SLICE_PADDING_SECONDS);
  const incomingSliceStart = Math.max(0, incomingCueTime - SLICE_PADDING_SECONDS);
  return {
    ok: true,
    transitionStart,
    transitionEnd,
    overlapSeconds,
    beats: OVERLAP_BEATS,
    bassSwapFraction,
    outgoingBpm,
    incomingBpm,
    stretchRatio,
    incomingCueTime,
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
