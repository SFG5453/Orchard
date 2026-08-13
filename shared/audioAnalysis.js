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

// 8: vocalActivityMask became a real per-frame curve. Version 7 entries carry
// the old track-wide scalar gated by loudness, which reads as plausible data
// but says nothing about any particular moment, so they must not be reused.
// 9: the native beat grid became tracked rather than extrapolated (its beats
// moved by up to 300 ms at the end of long tracks), and downbeats may come
// from the Beat This model. Cached version-8 grids are stale by construction.
// 10: lowEnergyCurve became measured sub-250 Hz spectral energy rather than a
// scaled copy of the broadband envelope. Cached version-9 curves cannot safely
// choose a content-aware bass handoff.
export const AUDIO_ANALYSIS_VERSION = 10;
export const MIN_LOCAL_BPM = 40;
export const MAX_LOCAL_BPM = 240;

export function isValidLocalBpm(value) {
  const bpm = Number(value);
  return Number.isFinite(bpm) && bpm >= MIN_LOCAL_BPM && bpm <= MAX_LOCAL_BPM;
}

export function isValidLocalAnalysis(value) {
  if (!value || value.analysisVersion !== AUDIO_ANALYSIS_VERSION || !isValidLocalBpm(value.bpm)) {
    return false;
  }
  const duration = Number(value.duration);
  const beatInterval = Number(value.beatInterval);
  return Number.isFinite(duration) && duration > 0 &&
    Number.isFinite(beatInterval) && beatInterval > 0 &&
    Array.isArray(value.beats) && value.beats.length >= 2 &&
    Array.isArray(value.downbeats) &&
    Array.isArray(value.phraseBoundaries);
}

export function localAnalysisWithSource(value, source) {
  if (!isValidLocalAnalysis(value)) return null;
  const bpm = Number(value.bpm);
  const originalSource = String(value.analysisSource || value.bpmSource || '');
  return {
    ...value,
    bpm,
    analyzedBpm: Number(value.analyzedBpm) || bpm,
    analyzedTempoConfidence: Number(value.analyzedTempoConfidence) ||
      Number(value.tempoConfidence) || Number(value.beatConfidence) || 0,
    analysisSource: source === 'cache' ? (originalSource || 'local-cache') : source,
    ...(source === 'cache' && originalSource ? { cachedBpmSource: originalSource } : {}),
    bpmSource: source
  };
}

function redactText(value) {
  return String(value ?? '')
    .replace(/\b(authorization|cookie|set-cookie|x-goog-visitor-id)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]')
    .replace(/https?:\/\/[^\s"']+/gi, (match) => {
      try {
        const url = new URL(match);
        return `${url.origin}${url.pathname}${url.search ? '?[redacted]' : ''}`;
      } catch {
        return '[redacted-url]';
      }
    })
    .slice(0, 1000);
}

export function safeAudioAnalysisDiagnostics(value, depth = 0) {
  if (depth > 3) return '[truncated]';
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (typeof value === 'string') return redactText(value);
  if (Array.isArray(value)) {
    return value.slice(0, 20).map((item) => safeAudioAnalysisDiagnostics(item, depth + 1));
  }
  if (typeof value !== 'object') return redactText(value);

  const output = {};
  Object.entries(value).slice(0, 50).forEach(([key, item]) => {
    if (/authorization|cookie|credential|signature|token/i.test(key)) {
      output[key] = '[redacted]';
      return;
    }
    output[key] = safeAudioAnalysisDiagnostics(item, depth + 1);
  });
  return output;
}
