// The confidence-aware transition policy shared by both smart-crossfade
// planners. The design follows the two-stage architecture every shipped
// auto-mix system converges on: analysis happens ahead of playback, and the
// runtime only decides how ambitious a transition the stored evidence can
// support. Ambition degrades in explicit tiers as certainty falls --
// beat-matched overlap, then a DJ-assisted crossfade, then a plain equal-power
// fade -- rather than letting one engine quietly do beat math on junk data.
//
// Every judgement here is made from stored analysis fields and their
// confidences; nothing in this module touches PCM.

// Below this the analyzer's beat grid is treated as a guess, and no renderer
// may stretch or phase-align against it. Catalog tempo lookups merge in with
// beatConfidence 0, so a metadata BPM alone can never authorize beat-matching.
export const MIN_BEATMATCH_CONFIDENCE = 0.55;

// Below this on both tracks, even the DJ-assisted crossfade (beat-quantized
// anchors, EQ handoff) is off the table and the mix degrades to a plain fade.
export const MIN_DJ_CONFIDENCE = 0.2;

// One octave either side of a typical dance tempo; outside this the analysis
// is treated as noise rather than a tempo.
export const MIN_BPM = 40;
export const MAX_BPM = 220;

// Mirrors kMaxTransparentRatioDeviation in native/transition/wsola.h.
export const MAX_STRETCH_DEVIATION = 0.04;

// A vocal-activity mask value at or above this counts as singing. The JS
// fallback analyzer emits a flat 0.5 mask, so unknown material never trips
// vocal logic; only the native analyzer's real mask can.
export const VOCAL_ACTIVE_THRESHOLD = 0.6;

export const TRANSITION_TIERS = ['beatmatched', 'dj_assisted', 'plain_crossfade'];

// How much of the outgoing track's remaining *music* a transition may skip by
// ending before its content does. A transition is allowed to leave a short
// tail unplayed; it is not allowed to cut the song short.
//
// This is the difference between mixing an outro and skipping one. The
// analyzer marks an outro up to 48 seconds before content end, and the
// silence-cliff detector will call any gap past the halfway mark a mix-out --
// anchoring to either without a budget throws away a minute of music the
// listener can hear is still coming. The outro is the part of a track written
// to have something else played over it, so the overlap belongs inside it and
// the transition still ends where the content does.
export const MAX_DISCARDED_MUSIC_SECONDS = 12;

// Fraction of a track's own loud-end reference below which a sample counts as
// silence rather than music, so a genuine gap costs nothing against the budget.
const AUDIBLE_ENERGY_FRACTION = 0.1;

function finiteOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

// Halves or doubles the incoming tempo until it is as close as possible to the
// outgoing, the way a DJ counts a 63 BPM track against a 126 BPM one.
export function alignTempoOctave(outgoingBpm, incomingBpm) {
  let aligned = incomingBpm;
  while (aligned / outgoingBpm > 1.5) aligned /= 2;
  while (aligned / outgoingBpm < 0.67) aligned *= 2;
  return aligned;
}

/**
 * Mean vocal activity over [start, end] on a track's own timeline, or null
 * when the analysis carries no usable mask there. The mask is indexed against
 * energyCurve sample times.
 */
export function vocalActivityBetween(analysis = {}, start = 0, end = 0) {
  const mask = Array.isArray(analysis.vocalActivityMask) ? analysis.vocalActivityMask : [];
  const curve = Array.isArray(analysis.energyCurve) ? analysis.energyCurve : [];
  if (!mask.length || mask.length !== curve.length || !(end > start)) return null;
  let sum = 0;
  let count = 0;
  for (let index = 0; index < mask.length; index += 1) {
    const time = Number(curve[index]?.time);
    if (!Number.isFinite(time) || time < start || time > end) continue;
    const value = Number(mask[index]);
    if (!Number.isFinite(value)) continue;
    sum += value;
    count += 1;
  }
  return count ? sum / count : null;
}

// Both windows measurably singing at once. Null means "no evidence", which
// never blocks -- absence of a mask is not absence of a vocal, but acting on
// it would punish every track the JS fallback analyzed.
export function isVocalClash(outgoingActivity, incomingActivity) {
  return outgoingActivity !== null &&
    incomingActivity !== null &&
    outgoingActivity >= VOCAL_ACTIVE_THRESHOLD &&
    incomingActivity >= VOCAL_ACTIVE_THRESHOLD;
}

/**
 * Seconds of audible music in [start, end] on a track's own timeline, judged
 * against the track's own loud-end reference so the measure is independent of
 * how the analyzer scales energy. Returns null when there is no usable curve.
 */
export function audibleSecondsBetween(analysis = {}, start = 0, end = 0) {
  const curve = Array.isArray(analysis.energyCurve) ? analysis.energyCurve : [];
  if (curve.length < 2 || !(end > start)) return null;
  const energies = curve
    .map((point) => Number(point?.energy))
    .filter((value) => Number.isFinite(value) && value >= 0)
    .sort((left, right) => left - right);
  if (!energies.length) return null;
  const reference = energies[Math.floor((energies.length - 1) * 0.85)] || 0;
  if (reference <= 0) return 0;
  const threshold = reference * AUDIBLE_ENERGY_FRACTION;
  const first = Number(curve[0]?.time);
  const last = Number(curve[curve.length - 1]?.time);
  if (!Number.isFinite(first) || !Number.isFinite(last) || last <= first) return null;
  const sampleSeconds = (last - first) / (curve.length - 1);
  let audible = 0;
  for (const point of curve) {
    const time = Number(point?.time);
    if (!Number.isFinite(time) || time < start || time > end) continue;
    if (Number(point?.energy) >= threshold) audible += sampleSeconds;
  }
  return audible;
}

function audibleStartOf(analysis = {}) {
  const candidates = [analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat]
    .map(Number)
    .filter((value) => Number.isFinite(value) && value >= 0);
  return candidates.length ? Math.min(...candidates) : 0;
}

function nearestValue(values, target, tolerance) {
  const candidates = (Array.isArray(values) ? values : [])
    .map(Number)
    .filter((value) => Number.isFinite(value) && Math.abs(value - target) <= tolerance);
  if (!candidates.length) return null;
  return candidates.reduce((best, value) =>
    Math.abs(value - target) < Math.abs(best - target) ? value : best
  );
}

// How much each candidate type is trusted as an entry point before scoring.
// Drops are where an arrangement arrives, so they dominate; a pickup is just
// "the file starts making sound" and a phrase boundary is only a grid line.
const MIX_IN_TYPE_WEIGHT = new Map([
  ['main_drop', 0.5],
  ['intro_drop', 0.4],
  ['pickup', 0.15],
  ['phrase', 0.1]
]);

/**
 * Ranks a track's analyzed mix-in candidates as entry points for a transition,
 * best first. Selection is a scoring problem, not a type lookup: the
 * analyzer's own score, the candidate type, downbeat alignment, whether there
 * is any intro before the point to bed under the outgoing track, and how
 * vocal that intro is all move a candidate up or down.
 */
export function rankMixInCandidates(analysis = {}) {
  const candidates = (Array.isArray(analysis.mixInCandidates) ? analysis.mixInCandidates : [])
    .filter((candidate) => Number.isFinite(Number(candidate?.time)) && Number(candidate.time) >= 0);
  if (!candidates.length) return [];
  const beatSeconds = finiteOrZero(analysis.beatInterval) ||
    (finiteOrZero(analysis.bpm) > 0 ? 60 / analysis.bpm : 0.5);
  const audibleStart = audibleStartOf(analysis);
  return candidates
    .map((candidate) => {
      const time = Number(candidate.time);
      let rankScore = finiteOrZero(candidate.score) +
        (MIX_IN_TYPE_WEIGHT.get(candidate.type) ?? 0);
      if (nearestValue(analysis.downbeats, time, beatSeconds / 2) !== null) rankScore += 0.1;
      // A cold open: nothing before the point to play underneath the outgoing
      // track, so entering here means starting the blend on the arrangement.
      if (time - audibleStart < beatSeconds * 4) rankScore -= 0.2;
      // Prefer entries whose run-up is instrumental; an intro that already
      // sings will sing over the outgoing track for the whole pre-roll.
      const vocal = vocalActivityBetween(
        analysis,
        Math.max(audibleStart, time - beatSeconds * 16),
        time
      );
      if (vocal !== null) rankScore += (0.5 - vocal) * 0.4;
      return { time, score: finiteOrZero(candidate.score), type: candidate.type, rankScore };
    })
    .sort((left, right) => right.rankScore - left.rankScore);
}

// Mirrors the analyzer's own scoring of mix-out candidates, used when an
// analysis carries the scalar fields but not the candidate list.
const MIX_OUT_TYPE_SCORE = new Map([
  ['interior_mix_out', 0.95],
  ['outro_start', 0.9],
  ['content_end', 0.75]
]);

function mixOutCandidatesOf(analysis = {}, contentEnd = 0) {
  const supplied = (Array.isArray(analysis.mixOutCandidates) ? analysis.mixOutCandidates : [])
    .filter((candidate) => Number.isFinite(Number(candidate?.time)) && Number(candidate.time) > 0);
  const candidates = supplied.length
    ? supplied.map((candidate) => ({
        time: Number(candidate.time),
        score: finiteOrZero(candidate.score),
        type: String(candidate.type || '')
      }))
    : [];
  if (!supplied.length) {
    const mixOut = finiteOrZero(analysis.mixOutTime);
    const outroStart = finiteOrZero(analysis.outroStartTime);
    if (mixOut > 0 && mixOut < contentEnd - 1) {
      candidates.push({ time: mixOut, score: 0.95, type: 'interior_mix_out' });
    }
    if (outroStart > 0 && outroStart < contentEnd - 1) {
      candidates.push({ time: outroStart, score: 0.9, type: 'outro_start' });
    }
  }
  // The transition always has somewhere to end: where the content does.
  if (!candidates.some((candidate) => Math.abs(candidate.time - contentEnd) < 0.05)) {
    candidates.push({ time: contentEnd, score: 0.75, type: 'content_end' });
  }
  return candidates;
}

/**
 * Ranks a track's analyzed mix-out candidates as places for a transition to
 * end, best first. Candidates that would skip more than the budget of
 * remaining music are dropped outright: how confidently the analyzer marked a
 * boundary is no argument for cutting a song short, and both an outro marker
 * and a mid-track silence gap will happily do exactly that. Silence is free,
 * so a genuine interior gap still wins the anchor it deserves.
 */
export function rankMixOutCandidates(analysis = {}, { contentEnd = 0, duration = 0 } = {}) {
  const end = finiteOrZero(contentEnd) ||
    finiteOrZero(analysis.contentEndTime) ||
    finiteOrZero(duration) ||
    finiteOrZero(analysis.duration);
  if (!(end > 0)) return [];
  return mixOutCandidatesOf(analysis, end)
    .map((candidate) => {
      const measured = audibleSecondsBetween(analysis, candidate.time, end);
      // With no energy curve there is no way to tell skipped music from
      // skipped silence, so the raw gap is charged in full and the budget
      // errs toward playing the track.
      const discardedMusicSeconds = measured === null
        ? Math.max(0, end - candidate.time)
        : measured;
      return {
        ...candidate,
        discardedMusicSeconds,
        measured: measured !== null,
        rankScore: candidate.score + (MIX_OUT_TYPE_SCORE.get(candidate.type) ?? 0)
      };
    })
    .filter((candidate) => candidate.discardedMusicSeconds <= MAX_DISCARDED_MUSIC_SECONDS)
    .sort((left, right) => right.rankScore - left.rankScore || right.time - left.time);
}

/**
 * Where the outgoing track's transition ends. The best-ranked mix-out
 * candidate that stays inside the discarded-music budget, or the end of
 * content when none does.
 */
export function resolveMixOutAnchor(analysis = {}, { contentEnd = 0, duration = 0 } = {}) {
  const end = finiteOrZero(contentEnd) ||
    finiteOrZero(analysis.contentEndTime) ||
    finiteOrZero(duration) ||
    finiteOrZero(analysis.duration);
  const best = rankMixOutCandidates(analysis, { contentEnd: end, duration })[0];
  return {
    time: best ? best.time : end,
    type: best ? best.type : 'content_end',
    discardedMusicSeconds: best ? best.discardedMusicSeconds : 0
  };
}

/**
 * Decides how ambitious a transition the stored analysis supports.
 *
 * - `beatmatched`: both beat grids are trusted and the tempi sit within the
 *   transparent stretch window; the WSOLA engine may render.
 * - `dj_assisted`: tempo exists and at least one grid is somewhat trusted;
 *   beat-quantized anchors and EQ handoffs are allowed, stretching is not.
 * - `plain_crossfade`: the evidence supports nothing beyond an equal-power
 *   fade placed at the analyzed mix-out anchor.
 *
 * Reasons are ordered most-disqualifying first so callers can surface
 * `reasons[0]` as the routing verdict.
 */
export function assessTransitionTier({ analysis = {}, nextAnalysis = {} } = {}) {
  const outgoingBpm = finiteOrZero(analysis.bpm);
  const incomingBpm = finiteOrZero(nextAnalysis.bpm);
  const outgoingConfidence = finiteOrZero(analysis.beatConfidence);
  const incomingConfidence = finiteOrZero(nextAnalysis.beatConfidence);
  const reasons = [];

  if (outgoingBpm < MIN_BPM || outgoingBpm > MAX_BPM) reasons.push('outgoing-tempo');
  if (incomingBpm < MIN_BPM || incomingBpm > MAX_BPM) reasons.push('incoming-tempo');
  if (reasons.length) {
    return { tier: 'plain_crossfade', reasons, beatConfidence: Math.min(outgoingConfidence, incomingConfidence) };
  }

  if (outgoingConfidence < MIN_DJ_CONFIDENCE && incomingConfidence < MIN_DJ_CONFIDENCE) {
    return {
      tier: 'plain_crossfade',
      reasons: ['beat-confidence'],
      beatConfidence: Math.min(outgoingConfidence, incomingConfidence)
    };
  }

  const stretchRatio = outgoingBpm / alignTempoOctave(outgoingBpm, incomingBpm);
  if (Math.abs(stretchRatio - 1) > MAX_STRETCH_DEVIATION) reasons.push('tempo-distance');
  if (
    outgoingConfidence < MIN_BEATMATCH_CONFIDENCE ||
    incomingConfidence < MIN_BEATMATCH_CONFIDENCE
  ) {
    reasons.push('beat-confidence');
  }

  return {
    tier: reasons.length ? 'dj_assisted' : 'beatmatched',
    reasons,
    beatConfidence: Math.min(outgoingConfidence, incomingConfidence)
  };
}
