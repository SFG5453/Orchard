/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

// Beat continuity, voices cut mid-line and the bass handover for one candidate overlap.
// Every measure is neutral when the worker sent no bass or vocal curves.
import { curveMean } from '../../../shared/trackAnalysis.js';
import { glideOffsets } from './pairTempoHarmony.js';
import { fadeSpansFor } from './transitionArrangement.js';

const FLOW_SAMPLES = 16;
// Bass curve levels (1 = the song's usual level) for "no beat" and "beat fully in".
const BASS_SILENT = 0.1;
const BASS_FULL = 0.5;
// Waiting this long for the beat after the old song leaves is a full failure.
const MAX_WAIT_SECONDS = 8;
const WAIT_SCAN_SECONDS = 24;
// Seconds of incoming music past its first beat that skipping counts against fully.
const MAX_SKIP_SECONDS = 45;
// A beat that stopped this many beats before the mix still counts as the listener's beat.
const RECENT_BEATS = 8;
// Vocal activity where a line is clearly in progress.
const VOICE_FLOOR = 0.35;
const VOICE_SPAN = 0.4;
// 808 weight contrast where two productions stop blending (boom-bap or R&B against trap).
// Blending those sounds like two DJs fighting over one booth, so the songs just play out.
export const STYLE_CLASH_CONTRAST = 0.9;
const firstBeats = new WeakMap();

function clamp(value, minimum = 0, maximum = 1) {
  return Number.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
}

function rounded(value) {
  return Math.round(value * 1e6) / 1e6;
}

/** Beat presence near `time`: 0 in a beatless passage, 1 once kick and bass are in. */
export function beatPresence(analysis, time) {
  const level = curveMean(analysis, 'bass', time - 0.5, time + 0.5);
  return level === null ? null : clamp((level - BASS_SILENT) / (BASS_FULL - BASS_SILENT));
}

/** Track time where the beat first comes in, cached per analysis. */
export function firstBeat(analysis) {
  if (firstBeats.has(analysis)) return firstBeats.get(analysis);
  const curve = analysis.curves?.bass;
  let found = null;
  for (let time = analysis.audibleRange?.start ?? 0; curve && time < curve.start + curve.values.length * curve.step; time += 0.5) {
    if ((beatPresence(analysis, time) ?? 0) >= 0.5) {
      found = time;
      break;
    }
  }
  firstBeats.set(analysis, found);
  return found;
}

// Where the beat comes in after a beatless intro: the mix-in a DJ would cue.
export function beatEntry(analysis, window) {
  const start = firstBeat(analysis);
  if (start === null || start < window.start || start > window.end) return null;
  const interval = Number.isFinite(analysis.timing?.beatInterval) ? analysis.timing.beatInterval : 0.5;
  const downbeat = (analysis.timing?.downbeats || [])
    .find((time) => time >= start - interval && time <= start + interval * 4);
  const time = downbeat ?? start;
  return {
    time,
    confidence: 0.75,
    source: 'beat-entry',
    evidence: { downbeatDistance: downbeat === undefined ? null : rounded(Math.abs(downbeat - start)) }
  };
}

/** Downbeats plus the lines half a bar later; double-time readings cannot tell them apart. */
export function halfBars(downbeats = [], interval = 0) {
  return interval > 0 ? downbeats.flatMap((time) => [time, time + interval * 2]) : downbeats;
}

// Floor rise (curve levels) from kick-only gaps to a held bassline: the drop a DJ cues.
const ARRIVAL_RISE = 0.4;

// Quietest half second over `from..to`: near zero between kicks, high under a held bass.
function bassFloor(analysis, from, to) {
  let floor = null;
  for (let time = from; time + 0.5 <= to + 1e-6; time += 0.5) {
    const level = curveMean(analysis, 'bass', time, time + 0.5);
    if (level === null) return null;
    floor = floor === null ? level : Math.min(floor, level);
  }
  return floor;
}

/**
 * First beat where a held bassline replaces a kick-only or filtered intro. Bar lines from a
 * double-time reading can sit half a bar off, so the curve picks the beat.
 */
export function bassArrival(analysis, window) {
  const interval = analysis.timing?.beatInterval;
  if (!analysis.curves?.bass || !(interval > 0)) return null;
  const span = interval * 4;
  // Beatless intros belong to `beatEntry`; this rule needs a bar of kicks before the drop.
  const kicks = firstBeat(analysis);
  if (kicks === null) return null;
  for (const time of analysis.timing?.beats || []) {
    if (time < Math.max(window.start, kicks) + span || time > window.end) continue;
    const before = bassFloor(analysis, time - span, time);
    const after = bassFloor(analysis, time, time + span);
    if (before === null || after === null || after - before < ARRIVAL_RISE) continue;
    // Fills raise the floor a bar early; the drop is the sharpest step on a half-bar line.
    let drop = null;
    let sharpest = -Infinity;
    for (const beat of halfBars(analysis.timing.downbeats, interval)) {
      if (beat < time - 1e-6 || beat > time + span - 1e-6) continue;
      const step = (curveMean(analysis, 'bass', beat, beat + 0.5) ?? 0) -
        (curveMean(analysis, 'bass', beat - 0.5, beat) ?? 0);
      if (step > sharpest) [drop, sharpest] = [beat, step];
    }
    if (drop === null || sharpest < ARRIVAL_RISE) continue;
    return { time: drop, confidence: 0.75, source: 'beat-entry', evidence: { bassRise: rounded(after - before) } };
  }
  return null;
}

// The beat the listener last heard: one that dropped out a bar or two before the mix
// is still missed, so mixing over the gap that follows is a loss too.
function recentBeat(analysis, time) {
  const interval = analysis.timing?.beatInterval;
  let level = beatPresence(analysis, time);
  if (level === null || !(interval > 0)) return level;
  for (let beat = 1; beat <= RECENT_BEATS; beat += 1) {
    level = Math.max(level, beatPresence(analysis, time - beat * interval) ?? 0);
  }
  return level;
}

function within(fraction, [start, end]) {
  return end > start ? clamp((fraction - start) / (end - start)) : fraction >= start ? 1 : 0;
}

function sourceOffsets(pair, time) {
  return pair.tempoRamp
    ? glideOffsets(time, pair.durationSeconds, pair.outgoingRatio, pair.incomingRatio)
    : { outgoing: time * pair.outgoingRatio, incoming: time * pair.incomingRatio };
}

/**
 * How well the beat the listener already hears carries through the overlap and
 * past the handoff. Equal-power gains weight each deck. Losing the outgoing beat
 * before the incoming one arrives is a dip; a beatless stretch after the old song
 * leaves is a delayed drop. A long beatless outro has nothing to lose, so only the wait counts.
 */
export function beatFlow(pair, outgoing, incoming) {
  if (!outgoing.curves?.bass || !incoming.curves?.bass || !(pair.durationSeconds > 0)) {
    return { score: 0.5, coverage: 0 };
  }
  const reference = recentBeat(outgoing, pair.outgoingStart);
  let deficit = 0;
  let worst = 0;
  let shared = 0;
  let known = 0;
  let late = 0;
  let secondHalf = 0;
  // A short unmatched fade is heard as a switch: only the beat after it counts.
  const samples = pair.beatmatched ? FLOW_SAMPLES : 0;
  const spans = fadeSpansFor(pair);
  for (let index = samples ? 0 : 1; index <= Math.max(1, samples); index += 1) {
    const fraction = samples ? index / samples : 1;
    const offsets = sourceOffsets(pair, fraction * pair.durationSeconds);
    const left = beatPresence(outgoing, pair.outgoingStart + offsets.outgoing);
    const right = beatPresence(incoming, pair.incomingStart + offsets.incoming + (samples ? 0 : 0.5));
    if (left === null || right === null || reference === null) continue;
    const out = Math.cos(within(fraction, spans.outgoing) * Math.PI / 2);
    const into = Math.sin(within(fraction, spans.incoming) * Math.PI / 2);
    const loss = Math.max(0, reference - Math.min(1, out * left + into * right));
    deficit += loss;
    worst = Math.max(worst, loss);
    if (samples && fraction >= 0.5) {
      secondHalf += 1;
      if (right < 0.5) late += 1;
    }
    if (left >= 0.5 && right >= 0.5) shared += 1;
    known += 1;
  }
  if (!known) return { score: 0.5, coverage: 0 };
  let waitSeconds = 0;
  for (let time = pair.incomingEnd; waitSeconds < WAIT_SCAN_SECONDS; time += 0.5) {
    const presence = beatPresence(incoming, time);
    if (presence === null || presence >= 0.5) break;
    waitSeconds += 0.5;
  }
  const meanDeficit = deficit / known;
  // The incoming beat should be in by the swap; one that lands as the mix ends is
  // the delayed drop listeners notice even when the outgoing beat covers the gap.
  const lateDrop = secondHalf ? (late / secondHalf) * (reference ?? 0) : 0;
  // DJs land the new song near its first beat; skipping deep into it costs a little.
  const start = firstBeat(incoming);
  const skip = start === null ? 0 : clamp((pair.incomingAnchor ?? pair.incomingEnd) - start, 0, MAX_SKIP_SECONDS) / MAX_SKIP_SECONDS;
  return {
    score: rounded(clamp(1 - meanDeficit * 0.5 - worst * 0.5 - lateDrop * 0.3 -
      clamp(waitSeconds / MAX_WAIT_SECONDS) * 0.6 - skip * 0.4)),
    lateDrop: rounded(lateDrop),
    meanDeficit: rounded(meanDeficit),
    worstDeficit: rounded(worst),
    waitSeconds: rounded(waitSeconds),
    skip: rounded(skip),
    sharedBeat: rounded(shared / known),
    coverage: rounded(known / (FLOW_SAMPLES + 1))
  };
}

export function voiceRisk(analysis, from, to) {
  const activity = curveMean(analysis, 'vocal', from, to);
  return activity === null ? null : rounded(clamp((activity - VOICE_FLOOR) / VOICE_SPAN));
}

/**
 * Fading the incoming in on a line already in progress: the voice arrives from nowhere. A
 * drop-in enters at full level on its anchor, so only a line already running before it counts.
 */
export function incomingVoiceRisk(pair, incoming) {
  if (pair.alignment !== 'start') {
    // Heard from where its fader starts to move.
    const heard = pair.incomingStart + (pair.spans?.incoming[0] ?? 0) * pair.durationSeconds * pair.incomingRatio;
    return voiceRisk(incoming, heard - 0.5, heard + 1);
  }
  // Nothing sings before a song starts.
  const curve = incoming.curves?.vocal;
  if (curve && pair.incomingStart - 1 < curve.start) return 0;
  return voiceRisk(incoming, pair.incomingStart - 1, pair.incomingStart - 0.1);
}

/** Source time where the outgoing falls silent: the overlap's end, or a drop-in's fader end. */
export function outgoingExit(pair) {
  const [, end] = fadeSpansFor(pair).outgoing;
  return pair.outgoingStart + (pair.outgoingEnd - pair.outgoingStart) * end;
}

/** Fading the outgoing out in the middle of a line, or muffling it there. */
export function outgoingVoiceRisk(pair, outgoing) {
  const exit = pair.voicesUntil === undefined
    ? outgoingExit(pair)
    : pair.outgoingStart + (pair.outgoingEnd - pair.outgoingStart) * pair.voicesUntil;
  return voiceRisk(outgoing, exit - 1.5, exit);
}

// A pause this long lets a line finish before the song ends.
const LINE_PAUSE_SECONDS = 2;
// Seconds at the song's end where its beat must be gone for the voice to count as an outro.
const OUTRO_SECONDS = 4;

/**
 * True when the voice runs from `time` to the end with no pause for a line to finish while the
 * song winds down: adlibs or a repeated hook that any exit, the song's own included, cuts off.
 * Songs that stop at full tilt keep the voice-cut veto; their last line is the main vocal.
 */
export function singsOut(analysis, time) {
  const end = analysis.audibleRange?.end;
  if (!Number.isFinite(end) || end - time < OUTRO_SECONDS) return false;
  const closing = beatPresence(analysis, end - OUTRO_SECONDS / 2);
  if (closing === null || closing >= 0.5 || (beatPresence(analysis, end - 0.5) ?? 1) >= 0.5) return false;
  let pause = 0;
  for (let at = time; at < end - 1; at += 0.25) {
    const risk = voiceRisk(analysis, at, at + 0.5);
    if (risk === null) return false;
    pause = risk < 0.3 ? pause + 0.25 : 0;
    if (pause >= LINE_PAUSE_SECONDS) return false;
  }
  return true;
}

/**
 * Bar-aligned overlap fraction where the low end changes hands: the first bar
 * near the middle where the incoming has a bass to hand to.
 */
export function bassSwapFraction(pair, incoming) {
  // A drop-in brings its bass with it.
  if (pair.alignment === 'start') return 0.05;
  const bars = pair.beats >= 8 ? Math.round(pair.beats / 4) : 0;
  if (!bars || !incoming.curves?.bass) return 0.5;
  for (let bar = 1; bar < bars; bar += 1) {
    const fraction = bar / bars;
    if (fraction < 0.375 || fraction > 0.75) continue;
    const offsets = sourceOffsets(pair, fraction * pair.durationSeconds);
    // Read just past the bar line: the incoming bass must be there once its cut opens.
    const presence = beatPresence(incoming, pair.incomingStart + offsets.incoming + 0.5);
    if (presence !== null && presence >= 0.5) return fraction;
  }
  return 0.5;
}

/** Bass level comparable across songs: the song's loudness plus its bass against its usual level. */
export function bassLevel(analysis, from, to) {
  const relative = curveMean(analysis, 'bass', from, to);
  const loudness = analysis.loudnessLufs;
  return relative > 0 && Number.isFinite(loudness) ? loudness + 20 * Math.log10(relative) : null;
}

// Swap moves: the old singer mid-line at a bar, clear of it at another, or the new bass rising.
// The side that carries the bass across the moved bars may be at most this many dB weaker.
const SWAP_WEAKER_DB = 3;
const SWAP_SINGING = 0.5;
const SWAP_CLEAR = 0.3;
const SWAP_DROP = 0.4;
const SWAP_FLAT = 0.2;

/**
 * The bass swap's bar line, chosen from the music. It starts where `bassSwapFraction` lands and
 * moves only for a reason a listener hears: the old singer is mid-line there but done at another
 * bar, or the new song's bass drops in at another bar while the old singer is clear of it.
 * Candidates nearest the middle win, since that is where the fades cross.
 */
export function chooseBassSwap(pair, outgoing, incoming) {
  const start = bassSwapFraction(pair, incoming);
  const bars = pair.beats >= 8 ? Math.round(pair.beats / 4) : 0;
  if (!bars || !incoming.curves?.bass || pair.alignment === 'start') return start;
  const read = (fraction) => {
    const offsets = sourceOffsets(pair, fraction * pair.durationSeconds);
    const inAt = pair.incomingStart + offsets.incoming;
    const outAt = pair.outgoingStart + offsets.outgoing;
    return {
      fraction,
      ready: (beatPresence(incoming, inAt + 0.5) ?? 0) >= 0.5,
      singing: voiceRisk(outgoing, outAt - 0.5, outAt + 0.5) ?? 0,
      drop: (curveMean(incoming, 'bass', inAt, inAt + 0.5) ?? 0) - (curveMean(incoming, 'bass', inAt - 0.5, inAt) ?? 0)
    };
  };
  // Moving the swap hands the bars in between to one side's bass, which must be playing there
  // and be about as strong as the bass it keeps out.
  const span = (from, to) => [sourceOffsets(pair, from * pair.durationSeconds), sourceOffsets(pair, to * pair.durationSeconds)];
  const carries = (from, to, side) => {
    const step = pair.durationSeconds / pair.beats;
    const [analysis, sourceStart] = side === 'incoming' ? [incoming, pair.incomingStart] : [outgoing, pair.outgoingStart];
    for (let at = from * pair.durationSeconds; at <= to * pair.durationSeconds + 1e-6; at += step) {
      if ((beatPresence(analysis, sourceStart + sourceOffsets(pair, at)[side]) ?? 0) < 0.5) return false;
    }
    const [a, b] = span(from, to);
    // Kicks read as bass; a bassline that only drops in between the bars is not there yet.
    const arrival = side === 'incoming' && bassArrival(incoming, {
      start: pair.incomingStart + a.incoming, end: pair.incomingStart + b.incoming + step
    });
    if (arrival && arrival.time > pair.incomingStart + a.incoming + 0.1) return false;
    const outLevel = bassLevel(outgoing, pair.outgoingStart + a.outgoing, pair.outgoingStart + b.outgoing);
    const inLevel = bassLevel(incoming, pair.incomingStart + a.incoming, pair.incomingStart + b.incoming);
    if (outLevel === null || inLevel === null) return true;
    return (side === 'incoming' ? inLevel - outLevel : outLevel - inLevel) >= -SWAP_WEAKER_DB;
  };
  const here = read(start);
  const moves = [];
  for (let bar = 1; bar < bars; bar += 1) {
    const fraction = bar / bars;
    if (fraction < 0.25 || fraction > 0.75 || Math.abs(fraction - start) < 1e-6) continue;
    const there = read(fraction);
    if (!there.ready || there.singing >= SWAP_CLEAR) continue;
    if (!(fraction < start ? carries(fraction, start, 'incoming') : carries(start, fraction, 'outgoing'))) continue;
    if (here.singing >= SWAP_SINGING || (there.drop >= SWAP_DROP && here.drop < SWAP_FLAT)) moves.push(there);
  }
  moves.sort((left, right) => Math.abs(left.fraction - 0.5) - Math.abs(right.fraction - 0.5) || left.fraction - right.fraction);
  return moves[0]?.fraction ?? start;
}

/** Difference in 808 weight between two productions; null without measurements. */
export function productionContrast(outgoing, incoming) {
  const left = outgoing?.subBassRatio;
  const right = incoming?.subBassRatio;
  return Number.isFinite(left) && Number.isFinite(right) ? rounded(Math.abs(left - right)) : null;
}
