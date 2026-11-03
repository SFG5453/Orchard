/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

// Muffle: a beatmatched mix that only works with the old song out of the way. The old song keeps
// its level while a steep low-pass closes over it, the way some songs muffle their own endings;
// the new song fades in over the same bars, takes the bass on the swap downbeat, and the muffled
// old song is gone two beats later.
import { curveMean } from '../../../shared/trackAnalysis.js';
import { evaluatePair } from './pairTransitionScoring.js';
import { PAIR_TRANSITION_POLICY, compareEvaluations, renderModeFor } from './pairTransitionGates.js';
import { bassLevel, bassSwapFraction, firstBeat } from './transitionFlow.js';

export const MUFFLE = Object.freeze({
  leadBeats: 8,
  minLeadBeats: 4,
  exitBeats: 2,
  // Three quarters into the close the low-pass sits near 700 Hz, past where a voice is understood.
  muted: 0.75,
  // Bass level drop, in dB, past which the new beat sounds weak after the old one.
  maxEnergyDrop: 5,
  levelSeconds: 8,
  // Seconds past the new song's first beat the mix may land. Behind the muffle a song can join
  // mid-verse; every entry heard so far landed within 40 s.
  maxSkipSeconds: 40,
  // Seconds of the new song that play before its fader moves; fading in its first notes loses its start.
  minOpeningSeconds: 2,
  // Old song's bass through the close, against its usual level; a thin song muffles to a murmur.
  minBody: 0.3,
  // A new singer fading in as the old one's line ends trades one voice for another.
  swapVoice: Object.freeze({ incoming: 0.7, outgoing: 0.5 })
});

function rounded(value) {
  return Math.round(value * 1e6) / 1e6;
}

/** How far the beat falls from the bars before the mix to the bars after it; null when unmeasured. */
export function energyDrop(pair, outgoing, incoming) {
  const before = bassLevel(outgoing, pair.outgoingStart - MUFFLE.levelSeconds, pair.outgoingStart);
  const after = bassLevel(incoming, pair.incomingEnd, pair.incomingEnd + MUFFLE.levelSeconds);
  return before === null || after === null ? null : before - after;
}

/** False when the muffle would bury the new song's opening, close over a thin song or swap singers. */
function muffleFits(pair, outgoing, incoming, lead, swap) {
  const time = (fraction, start, ratio) => start + fraction * pair.durationSeconds * ratio;
  const heard = time(lead, pair.incomingStart, pair.incomingRatio);
  if (heard - (incoming.audibleRange?.start ?? 0) < MUFFLE.minOpeningSeconds) return false;
  const closeFrom = time(lead, pair.outgoingStart, pair.outgoingRatio);
  const closeTo = time(swap, pair.outgoingStart, pair.outgoingRatio);
  const body = curveMean(outgoing, 'bass', closeFrom, closeTo);
  if (body !== null && body < MUFFLE.minBody) return false;
  const beat = pair.durationSeconds * pair.outgoingRatio / pair.beats;
  const leaving = curveMean(outgoing, 'vocal', closeFrom - beat, closeFrom);
  const arriving = curveMean(incoming, 'vocal', heard, time(swap, pair.incomingStart, pair.incomingRatio));
  return !(leaving >= MUFFLE.swapVoice.outgoing && arriving >= MUFFLE.swapVoice.incoming);
}

/**
 * Muffles for pairs that only failed for needing a filter, best first. Each re-evaluates its
 * pair with the short fader spans, so voices, beat flow and the exit point are judged on what
 * the listener actually hears.
 */
export function planMuffles(evaluations, context) {
  const muffles = [];
  for (const { pair, gates } of evaluations) {
    if (!pair.beatmatched || pair.alignment === 'start' || pair.loop) continue;
    if (!gates.some((gate) => gate.code === 'needs-filter')) continue;
    if (energyDrop(pair, context.outgoing, context.incoming) > MUFFLE.maxEnergyDrop) continue;
    const opening = firstBeat(context.incoming);
    if (opening !== null && (pair.incomingAnchor ?? pair.incomingEnd) - opening > MUFFLE.maxSkipSeconds) continue;
    const swap = bassSwapFraction(pair, context.incoming);
    const leadBeats = Math.min(MUFFLE.leadBeats, Math.round(swap * pair.beats));
    const exit = swap + MUFFLE.exitBeats / pair.beats;
    if (leadBeats < MUFFLE.minLeadBeats || exit > 1 + 1e-6) continue;
    const lead = Math.max(0, swap - leadBeats / pair.beats);
    if (!muffleFits(pair, context.outgoing, context.incoming, lead, swap)) continue;
    const spans = Object.freeze({
      outgoing: Object.freeze([rounded(swap), rounded(Math.min(1, exit))]),
      incoming: Object.freeze([rounded(lead), rounded(swap)])
    });
    // Long enough for a sustained run of two voices to show.
    const heard = Math.max((swap - lead) * MUFFLE.muted, PAIR_TRANSITION_POLICY.vocal.sustainedBeats / pair.beats);
    const evaluation = evaluatePair({
      ...pair, id: `${pair.id}:muffle`, exitStyle: 'muffle_swap', spans, voicesUntil: rounded(Math.min(swap, lead + heard))
    }, context);
    if (renderModeFor(evaluation.transitionClass) === 'native') muffles.push(evaluation);
  }
  return muffles.sort(compareEvaluations);
}
