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

// Playlist continuations encode a track offset, so later pages can load together
// instead of each waiting for the previous page's token.

const PAGE_SIZE = 100;
const WAVE_PAGES = 10;
// Bounds prefetched waves and remembered totals for abandoned playlists.
const maxPrefetched = 4;
const maxTotals = 32;

const prefetched = new Map();
const totals = new Map();

function varint(value) {
  let out = '';
  while (value > 127) {
    out += String.fromCharCode((value % 128) | 128);
    value = Math.floor(value / 128);
  }
  return out + String.fromCharCode(value);
}

const field = (number, text) => varint(number * 8 + 2) + varint(text.length) + text;
const unbase64 = (text) => atob(text + '='.repeat((4 - (text.length % 4)) % 4));

function readVarint(bytes, at) {
  let value = 0;
  let scale = 1;
  let byte;
  do {
    byte = bytes.charCodeAt(at.index++);
    if (Number.isNaN(byte)) throw new Error('Truncated varint.');
    value += (byte & 127) * scale;
    scale *= 128;
  } while (byte & 128);
  return value;
}

// Top-level varint and length-delimited fields of a protobuf message; null for other wire types.
function protoFields(bytes) {
  const fields = new Map();
  const at = { index: 0 };
  while (at.index < bytes.length) {
    const key = readVarint(bytes, at);
    const number = Math.floor(key / 8);
    if (key % 8 === 0) {
      fields.set(number, readVarint(bytes, at));
    } else if (key % 8 === 2) {
      const length = readVarint(bytes, at);
      fields.set(number, bytes.slice(at.index, at.index + length));
      at.index += length;
    } else {
      return null;
    }
  }
  return fields;
}

/** A WEB_REMIX playlist continuation for [offset]; [setVideoId] names the previous row. */
export function playlistToken(browseId, offset, setVideoId = '') {
  const position = '\x08' + varint(offset) + (setVideoId ? field(4, setVideoId) : '');
  const params = field(15, `PT:${btoa(position).replace(/=+$/, '')}`) + '\x92\x01\x03\x08\xba\x04\xf0\x01\x01';
  const body = field(2, browseId) + field(3, encodeURIComponent(btoa(params))) +
    field(35, browseId.replace(/^VL/, ''));
  return encodeURIComponent(btoa(field(80226972, body)));
}

/** {browseId, offset} when [token] is a playlist continuation this module reproduces exactly. */
export function parsePlaylistToken(token) {
  try {
    const body = protoFields(protoFields(unbase64(decodeURIComponent(token)))?.get(80226972) || '');
    const browseId = body?.get(2);
    const params = body?.get(3);
    if (typeof browseId !== 'string' || typeof params !== 'string') return null;
    const position = protoFields(unbase64(decodeURIComponent(params)))?.get(15);
    if (typeof position !== 'string' || !position.startsWith('PT:')) return null;
    const inner = protoFields(unbase64(position.slice(3)));
    const offset = inner?.get(1);
    const setVideoId = inner?.get(4) || '';
    if (!Number.isInteger(offset) || offset <= 0 || typeof setVideoId !== 'string') return null;
    // An unfamiliar layout could make extrapolated offsets skip rows.
    return playlistToken(browseId, offset, setVideoId) === token ? { browseId, offset } : null;
  } catch {
    return null;
  }
}

/** Remembers the header's "N tracks" so waves stop at the end. */
export function rememberPlaylistTotal(browseId, data) {
  const total = headerTrackTotal(data);
  if (!browseId || !total) return;
  totals.delete(browseId);
  totals.set(browseId, total);
  while (totals.size > maxTotals) totals.delete(totals.keys().next().value);
}

function headerTrackTotal(data) {
  const pending = [data?.header, data?.contents];
  for (let visited = 0; pending.length && visited < 2000; visited++) {
    const value = pending.pop();
    if (!value || typeof value !== 'object') continue;
    const runs = value.secondSubtitle?.runs;
    if (Array.isArray(runs)) {
      const match = runs.map((run) => run?.text || '').join('').match(/([\d,]+)\s+(?:songs?|tracks?|videos?)\b/i);
      if (match) return Number(match[1].replace(/,/g, ''));
    }
    pending.push(...Object.values(value));
  }
  return 0;
}

/**
 * Raw browse responses from [continuation] on: up to WAVE_PAGES pages fetched together when
 * the token's offset can be extrapolated, else the one page it names.
 */
async function fetchWave(fetchBrowse, rowCount, continuation) {
  const parsed = parsePlaylistToken(continuation);
  // Mixes regenerate and run on; hosts budget them per page.
  if (!parsed || parsed.browseId.replace(/^VL/, '').startsWith('RD')) return [await fetchBrowse({ continuation })];
  const total = totals.get(parsed.browseId) || 0;
  const offsets = [parsed.offset];
  while (offsets.length < WAVE_PAGES && (!total || offsets.at(-1) + PAGE_SIZE < total))
    offsets.push(offsets.at(-1) + PAGE_SIZE);
  const pages = await Promise.all(offsets.map((offset, index) =>
    fetchBrowse({ continuation: index === 0 ? continuation : playlistToken(parsed.browseId, offset) })
      .then((data) => ({ data }), (error) => ({ error }))));
  if (pages[0].error) throw pages[0].error;
  const kept = [];
  // A failed or short page ends the wave; its own continuation picks up from there.
  for (const page of pages) {
    if (page.error) break;
    kept.push(page.data);
    if (rowCount(page.data) !== PAGE_SIZE) break;
  }
  return kept;
}

/** Starts the wave at [continuation] while the current page crosses into the host. */
export function prefetchPlaylistWave(session, fetchBrowse, rowCount, continuation) {
  if (!continuation || prefetched.has(continuation)) return;
  const request = Promise.resolve()
    .then(() => fetchWave(fetchBrowse, rowCount, continuation))
    .then((pages) => ({ pages }), (error) => ({ error }));
  prefetched.set(continuation, { cookie: String(session?.cookie || '').trim(), request });
  while (prefetched.size > maxPrefetched) prefetched.delete(prefetched.keys().next().value);
}

/** The wave at [continuation], prefetched when possible. */
export async function playlistWave(session, fetchBrowse, rowCount, continuation) {
  const entry = prefetched.get(continuation);
  prefetched.delete(continuation);
  // A wave fetched for another account must not leak into this one.
  if (entry && entry.cookie === String(session?.cookie || '').trim()) {
    const { pages } = await entry.request;
    if (pages) return pages;
  }
  return fetchWave(fetchBrowse, rowCount, continuation);
}
