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

function normalizedKey(value) {
  return String(value || '').trim().toLowerCase();
}

function playableTracks(detail) {
  return (detail?.tracks || []).filter((track) => track?.id && track?.thumbnail);
}

function albumKey(track) {
  return normalizedKey(track.albumId) ||
    normalizedKey(track.album) ||
    normalizedKey(track.thumbnail);
}

export function playlistArtworkSeedTracks(detail) {
  if (detail?.kind !== 'playlist') return [];

  const seenAlbums = new Set();
  const seeds = [];

  for (const track of playableTracks(detail)) {
    const key = albumKey(track);
    if (key && seenAlbums.has(key)) continue;
    if (key) seenAlbums.add(key);
    seeds.push(track);
    if (seeds.length === 4) break;
  }

  return seeds;
}

export function playlistArtworkDetection(detail) {
  const seedTracks = playlistArtworkSeedTracks(detail);
  const canUseGeneratedCover = detail?.kind === 'playlist' &&
    Boolean(detail.thumbnail) &&
    seedTracks.length === 4;

  return {
    canUseGeneratedCover,
    seedTracks
  };
}
