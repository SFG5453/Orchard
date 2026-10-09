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

// Beat loops: when the outgoing beat stops before the song does, its last clean bars
// repeat under the mix. Past `loop.end` the outgoing timeline is virtual: time t plays
// source `loop.start + (t - loop.end) mod length`, and the renderer builds the same audio.
import { curvePeak } from '../../../shared/trackAnalysis.js';
import { beatPresence } from './transitionFlow.js';

// Loop lengths tried, longest first; two bars repeat less obviously than one.
const LOOP_BARS = [2, 1];
// Looped audio the planner may use past the loop end, in beats.
const LOOP_REACH_BEATS = 16;
// Repeating a voice sounds like a skipping CD, so no moment of the loop bars may carry
// one. A bar average hides a phrase that ends a beat into the loop.
const MAX_LOOP_VOICE = 0.2;
// Beat presence that counts as playing; the outro after the exit must average below it.
const BEAT_ON = 0.5;
// Beats fitted on each side of a point for the local grid.
const FIT_BEATS = 8;
// Longest beatless outro a loop may stand in for, in seconds.
const MAX_SKIPPED_OUTRO = 30;

function rounded(value) {
  return Math.round(value * 1e6) / 1e6;
}

/** Straight-line beat grid near `time`; tracker beats wobble by tens of milliseconds. */
export function localGrid(analysis, time) {
  const beats = analysis.timing?.beats || [];
  if (beats.length < 4) return null;
  let centre = 0;
  for (let index = 1; index < beats.length; index += 1) {
    if (Math.abs(beats[index] - time) < Math.abs(beats[centre] - time)) centre = index;
  }
  const from = Math.max(0, centre - FIT_BEATS);
  const to = Math.min(beats.length, centre + FIT_BEATS + 1);
  const count = to - from;
  const meanIndex = (from + to - 1) / 2;
  const meanTime = beats.slice(from, to).reduce((sum, beat) => sum + beat, 0) / count;
  let covariance = 0;
  let variance = 0;
  for (let index = from; index < to; index += 1) {
    covariance += (index - meanIndex) * (beats[index] - meanTime);
    variance += (index - meanIndex) ** 2;
  }
  const interval = covariance / variance;
  if (!(interval > 0)) return null;
  return { interval, at: (index) => meanTime + (index - meanIndex) * interval, centre };
}

// First downbeat where the beat drops out and stays mostly gone until the song ends.
function beatExit(analysis, from, end, barSeconds) {
  for (const downbeat of analysis.timing?.downbeats || []) {
    if (downbeat < Math.max(from, end - MAX_SKIPPED_OUTRO) || downbeat > end - 2 * barSeconds) continue;
    if (meanPresence(analysis, downbeat - barSeconds, downbeat) >= BEAT_ON &&
      meanPresence(analysis, downbeat, downbeat + barSeconds) < BEAT_ON &&
      meanPresence(analysis, downbeat, end) < BEAT_ON) return downbeat;
  }
  return null;
}

function meanPresence(analysis, from, to) {
  let total = 0;
  let count = 0;
  for (let time = from + 0.25; time < to; time += 0.5) {
    const presence = beatPresence(analysis, time);
    if (presence === null) continue;
    total += presence;
    count += 1;
  }
  return count ? total / count : 1;
}

function steadyBeat(analysis, grid, firstIndex, beats) {
  for (let index = firstIndex; index < firstIndex + beats; index += 1) {
    if ((beatPresence(analysis, grid.at(index) + grid.interval / 2) ?? 0) < BEAT_ON) return false;
  }
  return true;
}

/**
 * Bars to repeat under a mix, or null. Only songs whose beat ends before the song
 * does qualify, and only bars with a steady beat and no vocals.
 */
export function findOutgoingLoop(analysis, windowStart = null) {
  const interval = analysis.timing?.beatInterval;
  const beatsPerBar = analysis.timing?.meter?.beatsPerBar || 4;
  if (!analysis.curves?.bass || !analysis.curves?.vocal || !(interval > 0)) return null;
  const end = analysis.audibleRange?.end ?? analysis.duration;
  const from = Math.max(analysis.audibleRange?.start ?? 0, Number.isFinite(windowStart) ? windowStart : 0);
  const exit = beatExit(analysis, from + interval * beatsPerBar, end, interval * beatsPerBar);
  if (exit === null) return null;
  const grid = localGrid(analysis, exit);
  if (!grid) return null;
  for (const bars of LOOP_BARS) {
    const beats = bars * beatsPerBar;
    const startIndex = grid.centre - beats;
    const start = grid.at(startIndex);
    const loopEnd = grid.at(grid.centre);
    if (start < from || !steadyBeat(analysis, grid, startIndex, beats)) continue;
    const voice = curvePeak(analysis, 'vocal', start, loopEnd);
    if (voice === null || voice > MAX_LOOP_VOICE) continue;
    return { start: rounded(start), end: rounded(loopEnd), beats };
  }
  return null;
}

/** Source time the looped outgoing plays at timeline time `time`. */
export function loopSource(loop, time) {
  if (!loop || time < loop.end) return time;
  const length = loop.end - loop.start;
  return loop.start + ((time - loop.end) % length);
}

function loopTimes(times, loop, repeats) {
  const length = loop.end - loop.start;
  const inside = times.filter((time) => time >= loop.start && time < loop.end);
  const copies = [];
  for (let repeat = 1; repeat <= repeats; repeat += 1) {
    for (const time of inside) copies.push(rounded(time + repeat * length));
  }
  return Object.freeze([...times.filter((time) => time < loop.end), ...copies]);
}

function loopCurve(curve, loop, end) {
  if (!curve) return curve;
  const count = Math.max(0, Math.ceil((end - curve.start) / curve.step));
  const values = Array.from({ length: count }, (_, index) => {
    const source = loopSource(loop, curve.start + (index + 0.5) * curve.step);
    return curve.values[Math.floor((source - curve.start) / curve.step)] ?? null;
  });
  return Object.freeze({ ...curve, values: Object.freeze(values) });
}

/** The outgoing analysis as heard with `loop` repeating; candidates and scores read it as usual. */
export function loopedAnalysis(analysis, loop) {
  const length = loop.end - loop.start;
  const repeats = Math.ceil(LOOP_REACH_BEATS / loop.beats);
  const end = rounded(loop.end + repeats * length);
  const frames = analysis.frames.filter((frame) => frame.time < loop.end);
  for (let repeat = 1; repeat <= repeats; repeat += 1) {
    for (const frame of analysis.frames) {
      if (frame.time >= loop.start && frame.time < loop.end) {
        frames.push(Object.freeze({ ...frame, time: rounded(frame.time + repeat * length) }));
      }
    }
  }
  return Object.freeze({
    ...analysis,
    duration: end,
    audibleRange: Object.freeze({ ...analysis.audibleRange, end }),
    timing: Object.freeze({
      ...analysis.timing,
      beats: loopTimes(analysis.timing.beats, loop, repeats),
      downbeats: loopTimes(analysis.timing.downbeats, loop, repeats)
    }),
    frames: Object.freeze(frames),
    curves: Object.freeze({
      bass: loopCurve(analysis.curves.bass, loop, end),
      vocal: loopCurve(analysis.curves.vocal, loop, end)
    }),
    boundaries: Object.freeze(analysis.boundaries.filter((boundary) => boundary.time < loop.end)),
    loop
  });
}
