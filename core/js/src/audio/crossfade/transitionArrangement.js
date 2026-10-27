/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

// The overlap as the renderer executes it: which technique, when each side's fader moves,
// and where the bass changes hands. Every field here reaches the render; nothing is decorative.

export const TECHNIQUE = Object.freeze({
  BASS_SWAP: 'bass_swap',
  BLEND: 'beatmatched_blend',
  DROP_IN: 'drop_in',
  BRIDGE: 'beatless_bridge',
  MUFFLE: 'muffle_swap',
  BOUNDARY: 'boundary'
});

const FULL = Object.freeze([0, 1]);
// A drop-in's outgoing is gone by the middle of the overlap.
export const DROP_OUT_SPAN = 0.5;

/**
 * Fader spans a candidate's alignment implies. A drop-in ("start") lands the new song at full
 * level on its first beat and pulls the old one out over half the overlap; every other
 * alignment fades both sides across the whole overlap. A technique may set its own `spans`.
 */
export function fadeSpansFor(pair) {
  if (pair?.spans) return pair.spans;
  if (pair?.alignment !== 'start' || !(pair.beats > 0)) return { outgoing: FULL, incoming: FULL };
  return { outgoing: Object.freeze([0, DROP_OUT_SPAN]), incoming: Object.freeze([0, rounded(1 / pair.beats)]) };
}

function rounded(value) {
  return Math.round(value * 1e6) / 1e6;
}

function span(value) {
  if (!Array.isArray(value) || value.length !== 2) return FULL;
  const start = Math.max(0, Math.min(1, Number(value[0]) || 0));
  const end = Math.max(start, Math.min(1, Number(value[1]) || 0));
  return end - start < 0.02 ? FULL : Object.freeze([rounded(start), rounded(end)]);
}

/**
 * Fades are equal-power over their span of the overlap: the outgoing holds unity before its span
 * and is silent after it, the incoming is silent before its span and at unity after it.
 */
export function createArrangement({
  technique,
  outgoingFade = FULL,
  incomingFade = FULL,
  bassSwapFraction = null
} = {}) {
  if (!Object.values(TECHNIQUE).includes(technique)) {
    throw new Error(`Unknown transition technique '${technique}'`);
  }
  return Object.freeze({
    technique,
    outgoingFade: span(outgoingFade),
    incomingFade: span(incomingFade),
    bassSwapFraction: Number.isFinite(bassSwapFraction) ? rounded(bassSwapFraction) : null
  });
}

export const BOUNDARY_ARRANGEMENT = createArrangement({ technique: TECHNIQUE.BOUNDARY });
