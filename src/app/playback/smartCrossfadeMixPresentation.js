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

const MIN_OVERLAY_MS = 2800;
const MAX_OVERLAY_MS = 4800;

const STYLE_LABELS = {
  dj_blend: 'Beat blend',
  dj_filter: 'Filter mix',
  dj_switch: 'Phrase switch',
  equal_power: 'Smart fade',
  gapless: 'Gapless handoff',
  wsola_blend: 'Beat-matched mix'
};

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function trackArtist(track = {}) {
  if (track.artist) return String(track.artist);
  if (Array.isArray(track.artists) && track.artists.length) return track.artists.join(', ');
  return String(track.album || 'Orchard');
}

function deck(track = {}, artwork = '') {
  return {
    id: String(track.id || ''),
    title: String(track.title || 'Unknown track'),
    artist: trackArtist(track),
    artwork: String(artwork || track.thumbnail || '')
  };
}

function roundedBpm(analysis = {}) {
  const bpm = Number(analysis.bpm);
  return Number.isFinite(bpm) && bpm > 0 ? Math.round(bpm) : 0;
}

function keyLabel(analysis = {}) {
  return String(analysis.key || '').trim();
}

export function smartCrossfadeOverlayDuration(fadeSeconds = 0) {
  const seconds = Number(fadeSeconds);
  if (!Number.isFinite(seconds) || seconds <= 0) return MIN_OVERLAY_MS;
  return Math.round(clamp(2100 + seconds * 160, MIN_OVERLAY_MS, MAX_OVERLAY_MS));
}

export function createSmartCrossfadeMixPresentation({
  id = 0,
  fromTrack,
  toTrack,
  currentArtwork = '',
  transition = {},
  analysis = {},
  nextAnalysis = {}
} = {}) {
  const playbackRate = Number(transition.incomingPlaybackRate) || 1;
  const tempoShift = Math.round((playbackRate - 1) * 100);
  const style = String(transition.transitionStyle || 'equal_power');

  const fadeMs = Math.max(1000, Math.round((Number(transition.fadeSeconds) || 0) * 1000));

  return {
    id,
    visible: true,
    durationMs: smartCrossfadeOverlayDuration(transition.fadeSeconds),
    fadeDurationMs: fadeMs,
    style,
    styleLabel: STYLE_LABELS[style] || 'Smart mix',
    from: deck(fromTrack, currentArtwork),
    to: deck(toTrack),
    fromBpm: roundedBpm(analysis),
    toBpm: roundedBpm(nextAnalysis),
    fromKey: keyLabel(analysis),
    toKey: keyLabel(nextAnalysis),
    tempoShift,
    transitionBeats: Math.max(0, Math.round(Number(transition.transitionBeats) || 0))
  };
}
