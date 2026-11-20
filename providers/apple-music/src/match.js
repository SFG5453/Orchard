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

// Finds the Apple catalog song behind a track from any service.

const CANDIDATES = 10;

export function normalized(value) {
  return String(value || '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

/** Drops "(feat. X)", "[Deluxe]" and " - Single" so YouTube and Apple spellings meet. */
function editionless(value) {
  const stripped = String(value || '')
    .replace(/\s*[([][^)\]]*[)\]]/g, '')
    .replace(/\s+-\s+[^-]*\b(single|ep|deluxe|edition|version|remaster(ed)?|expanded|anniversary|bonus)\b.*$/i, '');
  return normalized(stripped) || normalized(value);
}

export function sameTitle(left, right) {
  const a = editionless(left);
  return a !== '' && a === editionless(right);
}

export function sameArtist(left, right) {
  const a = normalized(left);
  const b = normalized(right);
  return a !== '' && b !== '' && (a === b || a.includes(b) || b.includes(a));
}

/** YouTube upload titles carry "(Official Video)" and "Artist - Topic"; Apple search does not. */
function searchText(value) {
  return String(value || '')
    .replace(/[([][^)\]]*(official|video|visualizer|lyrics?|audio|hd|4k)[^)\]]*[)\]]/gi, '')
    .replace(/\s+-\s+Topic$/i, '')
    .replace(/\s+/g, ' ')
    .trim();
}

// Catalog clocks and Apple's durationInMillis disagree by a second or two at most.
const DURATION_TOLERANCE_MS = 3000;

function pickSong(songs, { title, artist, album, durationSeconds }) {
  let matches = songs.filter((song) =>
    sameTitle(song.attributes?.name, title) && sameArtist(song.attributes?.artistName, artist));
  // editionless() folds "(Live)" and "(Acoustic)" into the studio title, so a known length
  // decides between cuts; lyrics timed to another cut drift from what is playing.
  const wantedMs = Number(durationSeconds) * 1000;
  if (wantedMs > 0) {
    const gap = (song) => Math.abs(Number(song.attributes?.durationInMillis) - wantedMs);
    const close = matches.filter((song) => gap(song) <= DURATION_TOLERANCE_MS).sort((left, right) => gap(left) - gap(right));
    if (close.length) matches = close;
  }
  return matches.find((song) => album && sameTitle(song.attributes?.albumName, album)) || matches[0] || null;
}

/** The catalog song resource for an Apple id, an ISRC, or title/artist/album text; null when none matches. */
export async function findSong(client, { appleId, isrc, title, artist, album, durationSeconds } = {}) {
  const path = (suffix = '') => `/v1/catalog/${client.storefront()}/${suffix}`;
  // Library-only ids (i.xxx) have no catalog song.
  if (appleId && !String(appleId).includes('.'))
    return (await client.get(path(`songs/${encodeURIComponent(appleId)}`))).data?.[0] || null;
  const query = { title: searchText(title), artist: searchText(artist), album, durationSeconds };
  if (isrc) {
    const songs = (await client.get(path('songs'), { 'filter[isrc]': isrc })).data || [];
    const song = pickSong(songs, query) || songs[0];
    if (song) return song;
  }
  if (!query.title || !query.artist) return null;
  const result = await client.get(path('search'), { term: `${query.title} ${query.artist}`, types: 'songs', limit: CANDIDATES });
  return pickSong(result.results?.songs?.data || [], query);
}
