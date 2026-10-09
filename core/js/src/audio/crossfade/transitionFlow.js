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

// Beat continuity, voices cut mid-line and the bass handover for one candidate overlap.
// Every measure is neutral when the worker sent no bass or vocal curves.
import { curveMean } from '../../../shared/trackAnalysis.js';
import { glideOffsets } from './pairTempoHarmony.js';

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
// Above this, productions only blend cleanly; one that needs a filter plays out too.
export const STYLE_RISK_CONTRAST = 0.6;
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
  for (let index = samples ? 0 : 1; index <= Math.max(1, samples); index += 1) {
    const fraction = samples ? index / samples : 1;
    const offsets = sourceOffsets(pair, fraction * pair.durationSeconds);
    const left = beatPresence(outgoing, pair.outgoingStart + offsets.outgoing);
    const right = beatPresence(incoming, pair.incomingStart + offsets.incoming + (samples ? 0 : 0.5));
    if (left === null || right === null || reference === null) continue;
    const angle = fraction * Math.PI / 2;
    const loss = Math.max(0, reference - Math.min(1, Math.cos(angle) * left + Math.sin(angle) * right));
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
      clamp(waitSeconds / MAX_WAIT_SECONDS) * 0.6 - skip * 0.15)),
    lateDrop: rounded(lateDrop),
    meanDeficit: rounded(meanDeficit),
    worstDeficit: rounded(worst),
    waitSeconds: rounded(waitSeconds),
    skip: rounded(skip),
    sharedBeat: rounded(shared / known),
    coverage: rounded(known / (FLOW_SAMPLES + 1))
  };
}

function voiceRisk(analysis, from, to) {
  const activity = curveMean(analysis, 'vocal', from, to);
  return activity === null ? null : rounded(clamp((activity - VOICE_FLOOR) / VOICE_SPAN));
}

/** Fading the incoming in on a line already in progress: the voice arrives from nowhere. */
export function incomingVoiceRisk(pair, incoming) {
  return voiceRisk(incoming, pair.incomingStart - 0.5, pair.incomingStart + 1);
}

/** Fading the outgoing out in the middle of a line. */
export function outgoingVoiceRisk(pair, outgoing) {
  return voiceRisk(outgoing, pair.outgoingEnd - 1.5, pair.outgoingEnd);
}

/**
 * Bar-aligned overlap fraction where the low end changes hands: the first bar
 * near the middle where the incoming has a bass to hand to.
 */
export function bassSwapFraction(pair, incoming) {
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

/** Difference in 808 weight between two productions; null without measurements. */
export function productionContrast(outgoing, incoming) {
  const left = outgoing?.subBassRatio;
  const right = incoming?.subBassRatio;
  return Number.isFinite(left) && Number.isFinite(right) ? rounded(Math.abs(left - right)) : null;
}
