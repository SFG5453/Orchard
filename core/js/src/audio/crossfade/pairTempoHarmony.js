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

// Tempo fits, glide offsets and key/chroma compatibility for a track pair.
export const MAX_STRETCH_DEVIATION = 0.04;
// Peak per-track deviation of a tempo glide. Each side is furthest from native
// where its gain is lowest, so the glide tolerates more than a constant stretch.
// Listening tests: a 6% glide blended cleanly, an 11.8% glide sounded forced.
export const MAX_RAMP_DEVIATION = 0.08;

const ROOTS = new Map([
  ['C', 0], ['B#', 0],
  ['C#', 1], ['C♯', 1], ['DB', 1], ['D♭', 1],
  ['D', 2],
  ['D#', 3], ['D♯', 3], ['EB', 3], ['E♭', 3],
  ['E', 4], ['FB', 4],
  ['F', 5], ['E#', 5],
  ['F#', 6], ['F♯', 6], ['GB', 6], ['G♭', 6],
  ['G', 7],
  ['G#', 8], ['G♯', 8], ['AB', 8], ['A♭', 8],
  ['A', 9],
  ['A#', 10], ['A♯', 10], ['BB', 10], ['B♭', 10],
  ['B', 11], ['CB', 11]
]);

function finite(value) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function clamp(value, minimum = 0, maximum = 1) {
  const number = finite(value);
  return number === null ? minimum : Math.max(minimum, Math.min(maximum, number));
}

function rounded(value, places = 6) {
  const scale = 10 ** places;
  return Math.round(value * scale) / scale;
}

export function ratioDeviation(...ratios) {
  return Math.max(...ratios.map((ratio) => Math.abs(ratio - 1)));
}

// One shared tempo glides geometrically from the outgoing BPM to the incoming
// BPM, so the outgoing starts and the incoming ends at native speed. Ratios are
// overlap averages: N beats at the log-mean tempo still consume N source beats.
function rampFit(outgoing, incoming) {
  const glide = incoming / outgoing;
  const deviation = ratioDeviation(glide, 1 / glide);
  if (deviation > MAX_RAMP_DEVIATION) {
    return {
      outgoingBpm: outgoing,
      incomingBpm: incoming,
      targetBpm: outgoing,
      outgoingRatio: 1,
      incomingRatio: 1,
      deviation: rounded(deviation),
      beatmatched: false,
      tempoRamp: true
    };
  }
  const logMean = Math.abs(glide - 1) < 1e-9
    ? outgoing
    : (incoming - outgoing) / Math.log(glide);
  return {
    outgoingBpm: outgoing,
    incomingBpm: incoming,
    targetBpm: rounded(logMean),
    outgoingRatio: rounded(logMean / outgoing),
    incomingRatio: rounded(logMean / incoming),
    deviation: rounded(deviation),
    beatmatched: true,
    tempoRamp: true
  };
}

/**
 * Source seconds each side has consumed `time` seconds into a tempo glide with
 * the given average ratios.
 */
export function glideOffsets(time, duration, outgoingRatio, incomingRatio) {
  const glide = outgoingRatio / incomingRatio;
  const outgoing = duration > 0 && Math.abs(glide - 1) > 1e-9
    ? duration * (glide ** (time / duration) - 1) / Math.log(glide)
    : time * outgoingRatio;
  return { outgoing, incoming: outgoing / glide };
}

export function tempoFit(outgoingBpm, incomingBpm, { tempoRamp = false } = {}) {
  const outgoing = finite(outgoingBpm) ?? 0;
  let incoming = finite(incomingBpm) ?? 0;
  if (!(outgoing > 0) || !(incoming > 0)) {
    return {
      outgoingBpm: outgoing,
      incomingBpm: incoming,
      targetBpm: outgoing,
      outgoingRatio: 1,
      incomingRatio: 1,
      deviation: 0,
      beatmatched: false
    };
  }
  while (incoming / outgoing > 1.5) incoming /= 2;
  while (incoming / outgoing < 0.67) incoming *= 2;
  incoming = rounded(incoming);
  if (tempoRamp) return rampFit(outgoing, incoming);
  const outgoingRatio = incoming / outgoing;
  const incomingRatio = 1;
  const deviation = ratioDeviation(outgoingRatio, incomingRatio);
  if (deviation > MAX_STRETCH_DEVIATION) {
    return {
      outgoingBpm: outgoing,
      incomingBpm: incoming,
      targetBpm: outgoing,
      outgoingRatio: 1,
      incomingRatio: 1,
      deviation: rounded(deviation),
      beatmatched: false
    };
  }
  return {
    outgoingBpm: outgoing,
    incomingBpm: incoming,
    targetBpm: incoming,
    outgoingRatio: rounded(outgoingRatio),
    incomingRatio,
    deviation: rounded(deviation),
    beatmatched: true
  };
}

function parsedKey(value) {
  const match = String(value || '').trim().match(/^([^\s]+)\s+(major|minor)$/i);
  if (!match) return null;
  const root = ROOTS.get(match[1].toUpperCase()) ?? ROOTS.get(match[1]);
  return Number.isInteger(root) ? { root, mode: match[2].toLowerCase() } : null;
}

function keyRelationship(left, right) {
  if (!left || !right) return null;
  const clockwise = (right.root - left.root + 12) % 12;
  const distance = Math.min(clockwise, 12 - clockwise);
  if (left.mode !== right.mode) {
    const relative = (left.mode === 'major' && clockwise === 9) ||
      (left.mode === 'minor' && clockwise === 3);
    if (relative) return 0.95;
    if (distance === 0) return 0.7;
    if (distance === 5) return 0.55;
    if (distance === 6) return 0.05;
    return 0.35;
  }
  if (distance === 0) return 1;
  if (distance === 5) return 0.85;
  if (distance === 2) return 0.65;
  if (distance === 1) return 0.3;
  if (distance === 6) return 0;
  return 0.45;
}

function chromaSimilarity(left, right) {
  if (!Array.isArray(left) || left.length !== 12 || !Array.isArray(right) || right.length !== 12) {
    return null;
  }
  let dot = 0;
  let leftPower = 0;
  let rightPower = 0;
  for (let index = 0; index < 12; index += 1) {
    const a = finite(left[index]) ?? 0;
    const b = finite(right[index]) ?? 0;
    dot += a * b;
    leftPower += a * a;
    rightPower += b * b;
  }
  if (leftPower <= 0 || rightPower <= 0) return null;
  return clamp(dot / Math.sqrt(leftPower * rightPower), 0, 1);
}

export function harmonicEvidence(outgoing = {}, incoming = {}) {
  const left = outgoing.harmonic || {};
  const right = incoming.harmonic || {};
  const keyScore = keyRelationship(parsedKey(left.key), parsedKey(right.key));
  const chromaScore = chromaSimilarity(left.chroma, right.chroma);
  const score = keyScore !== null && chromaScore !== null
    ? keyScore * 0.7 + chromaScore * 0.3
    : keyScore ?? chromaScore ?? 0.5;
  const confidence = Math.sqrt(
    clamp(left.keyConfidence, 0, 1) * clamp(right.keyConfidence, 0, 1)
  );
  return {
    score: rounded(score),
    confidence: rounded(confidence),
    keyScore: keyScore === null ? null : rounded(keyScore),
    chromaScore: chromaScore === null ? null : rounded(chromaScore),
    severeClash: confidence >= 0.65 && score < 0.2
  };
}
