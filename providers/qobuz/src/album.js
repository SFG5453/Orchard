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

import { normalizedQobuzText } from './matcher.js';
import { normalizeQobuzAlbumQuality } from './quality.js';
import { normalizeQobuzQuality } from './types.js';

const EDITION = /\s*[([][^)\]]*\b(deluxe|edition|remaster(ed)?|expanded|anniversary|version|bonus)\b[^)\]]*[)\]]\s*$/i;

function editionless(value) {
  let text = String(value || '');
  for (let previous = ''; previous !== text;) {
    previous = text;
    text = text.replace(EDITION, '').replace(/\s+-\s+(single|ep)$/i, '');
  }
  return normalizedQobuzText(text);
}

function fullTitle(album) {
  const title = String(album?.title || '').trim();
  const version = String(album?.version || '').trim();
  return version && !title.toLowerCase().includes(version.toLowerCase()) ? `${title} (${version})` : title;
}

function artistMatches(target, album) {
  const wanted = normalizedQobuzText(target);
  const offered = [album?.artist?.name, ...(album?.artists || []).map((artist) => artist?.name)]
    .map(normalizedQobuzText)
    .filter(Boolean);
  return Boolean(wanted) && offered.some((name) => name === wanted ||
    (Math.min(name.length, wanted.length) >= 4 && (name.includes(wanted) || wanted.includes(name))));
}

function releaseYear(album) {
  const date = String(album?.release_date_original || album?.release_date_stream || '');
  const year = Number(date.slice(0, 4));
  if (year > 1900) return year;
  const released = Number(album?.released_at || 0);
  return released > 0 ? new Date(released * 1000).getUTCFullYear() : 0;
}

function score(target, album) {
  if (album?.streamable === false || !artistMatches(target.artist, album)) return null;
  const exact = normalizedQobuzText(fullTitle(album)) === normalizedQobuzText(target.title);
  if (!exact && editionless(fullTitle(album)) !== editionless(target.title)) return null;
  const count = Number(album.tracks_count || 0);
  const year = releaseYear(album);
  return (exact ? 40 : 30) +
    (target.trackCount && count ? Math.max(0, 10 - Math.abs(count - target.trackCount) * 2) : 0) +
    (target.year && year ? (year === target.year ? 5 : -5) : 0);
}

/**
 * Picks the Qobuz album behind a catalog album and states the best quality
 * this account will stream from it, capped by the chosen quality setting.
 */
export function selectQobuzAlbum(target, candidates = [], quality = 'auto') {
  const wanted = {
    title: String(target?.title || ''),
    artist: String(target?.artist || ''),
    year: Number(target?.year || 0) || 0,
    trackCount: Number(target?.trackCount || 0) || 0
  };
  if (!wanted.title || !wanted.artist) return null;
  const ranked = candidates
    .map((album) => ({ album, score: score(wanted, album) }))
    .filter((entry) => entry.score !== null)
    .sort((left, right) => right.score - left.score);
  if (!ranked.length) return null;

  const { album } = ranked[0];
  const metadata = normalizeQobuzAlbumQuality(album);
  const hiresAvailable = (metadata.hiresStreamable !== false || album.hires === true) &&
    ((metadata.bitDepth || 0) > 16 || (metadata.sampleRate || 0) > 48_000);
  const hires = hiresAvailable && normalizeQobuzQuality(quality) !== 'lossless';
  return {
    albumId: String(album.id),
    title: fullTitle(album),
    tier: hires ? 'hires' : 'lossless',
    bitDepth: hires ? metadata.bitDepth : 16,
    sampleRate: hires ? metadata.sampleRate : 44_100
  };
}

export function albumCandidates(response = {}) {
  return (response.albums?.items || []).filter((album) => album?.id !== undefined);
}
