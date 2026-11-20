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

// Coordinates lyrics providers and converts their formats to the renderer-facing result shape.
// Runs in bare QuickJS: no URL, URLSearchParams, or abortable fetch.
import { parseAmLyricsTtml, parseAmLyricsPayload, normalizeAmLyricsLines, parseLocalLyrics } from './lyricsParsers.js';
import { queryString } from '../shared/queryString.js';
import { resolveBetterLyrics } from './betterLyricsProvider.js';

const lrclibBaseUrl = 'https://lrclib.net/api';
const amLyricsFetchTimeoutMs = 8000;
const amLyricsServers = [
  'https://lyricsplus.binimum.org',
  'https://lyricsplus-seven.vercel.app',
  'https://lyricsplus.prjktla.workers.dev',
  'https://lyrics-plus-backend.vercel.app'
];

function lyricsLookupText(value = '') {
  return String(value || '')
    .replace(/\([^)]*(official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^)]*\)/gi, '')
    .replace(/\[[^\]]*(official|video|visualizer|lyrics?|audio|remaster|hd|4k)[^\]]*\]/gi, '')
    .replace(/\s+-\s+Topic$/i, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function lyricsDurationMs(track = {}) {
  let seconds = Number(track.durationSeconds || 0);
  if (!(seconds > 0) && /^\d+(:\d{1,2})+$/.test(String(track.duration || '').trim())) {
    seconds = String(track.duration).trim().split(':').reduce((total, part) => total * 60 + Number(part), 0);
  }
  return seconds > 0 ? Math.round(seconds * 1000) : undefined;
}

function lyricsMetadata(track = {}) {
  const title = lyricsLookupText(track.title);
  const artist = lyricsLookupText(track.artist || track.artists?.[0] || '');
  if (!title || !artist) return null;

  return {
    title,
    artist,
    album: lyricsLookupText(track.album) || '',
    durationMs: lyricsDurationMs(track),
    videoId: String(track.id || '').trim()
  };
}

function lyricsQuery(track = {}) {
  const metadata = lyricsMetadata(track);
  if (!metadata) return null;

  return {
    track_name: metadata.title,
    artist_name: metadata.artist,
    album_name: metadata.album || undefined,
    duration: metadata.durationMs
  };
}

// The host fetch cannot abort, so a slow mirror is abandoned rather than cancelled.
async function fetchWithTimeout(url, options = {}, timeoutMs = amLyricsFetchTimeoutMs) {
  let timeout;
  const expired = new Promise((_, reject) => {
    timeout = setTimeout(() => reject(new Error('Lyrics request timed out.')), timeoutMs);
  });

  try {
    return await Promise.race([fetch(url, options), expired]);
  } finally {
    clearTimeout(timeout);
  }
}

async function resolveBiniLyrics(metadata) {
  const query = queryString({
    track: metadata.title,
    artist: metadata.artist,
    album: metadata.album,
    duration: metadata.durationMs ? Math.round(metadata.durationMs / 1000) : undefined
  });

  const response = await fetchWithTimeout(`https://lyrics-api.binimum.org/?${query}`);
  if (!response.ok) return null;

  const payload = await response.json();
  const lyricsUrl = payload?.results?.find?.((item) => item?.lyricsUrl)?.lyricsUrl;
  if (!lyricsUrl) return null;

  const lyricsResponse = await fetchWithTimeout(lyricsUrl);
  if (!lyricsResponse.ok) return null;

  return parseAmLyricsTtml(await lyricsResponse.text());
}

async function resolveLyricsPlusLyrics(metadata) {
  const query = queryString({
    title: metadata.title,
    artist: metadata.artist,
    album: metadata.album,
    duration: metadata.durationMs ? Math.round(metadata.durationMs / 1000) : undefined
  });

  for (const base of amLyricsServers) {
    try {
      const response = await fetchWithTimeout(`${base}/v2/lyrics/get?${query}`);
      if (!response.ok) continue;

      const lines = parseAmLyricsPayload(await response.json());
      if (lines.length) return lines;
    } catch {
      // Try the next LyricsPlus mirror.
    }
  }

  return [];
}

async function lrclibRequest(path, params) {
  const response = await fetchWithTimeout(`${lrclibBaseUrl}/${path}?${queryString(params)}`, {
    headers: { 'Lrclib-Client': 'Orchard' }
  });
  if (!response.ok) throw new Error(`LRCLIB request failed with HTTP ${response.status}.`);
  return response.json();
}

function normalizeLyricsResult(result) {
  if (!result) return { status: 'unavailable', mode: '', lines: [], source: 'lrclib' };

  if (result.instrumental) {
    return {
      status: 'ready',
      mode: 'unsynced',
      lines: [{ text: 'Instrumental' }],
      source: 'lrclib'
    };
  }

  if (result.syncedLyrics) {
    const parsed = parseLocalLyrics(result.syncedLyrics);
    if (parsed.synced?.length) {
      return {
        status: 'ready',
        mode: 'synced',
        lines: parsed.synced,
        source: 'lrclib'
      };
    }
  }

  if (result.plainLyrics) {
    const parsed = parseLocalLyrics(result.plainLyrics);
    if (parsed.unsynced.length) {
      return {
        status: 'ready',
        mode: 'unsynced',
        lines: parsed.unsynced,
        source: 'lrclib'
      };
    }
  }

  return { status: 'unavailable', mode: '', lines: [], source: 'lrclib' };
}

async function resolveLrclibLyrics(track = {}) {
  const query = lyricsQuery(track);
  if (!query) return { status: 'unavailable', mode: '', lines: [], source: 'lrclib' };

  const duration = query.duration ? query.duration / 1000 : undefined;
  try {
    return normalizeLyricsResult(await lrclibRequest('get', { ...query, duration }));
  } catch {
    try {
      const search = await lrclibRequest('search', {
        track_name: query.track_name,
        artist_name: query.artist_name,
        duration
      });
      const firstWithLyrics = (Array.isArray(search) ? search : [])
        .find((item) => item.syncedLyrics || item.plainLyrics || item.instrumental);
      return normalizeLyricsResult(firstWithLyrics);
    } catch {
      return { status: 'unavailable', mode: '', lines: [], source: 'lrclib' };
    }
  }
}

// Lyrics: the only part of a song everyone is confident about until the chorus.
// [appleTtml] is Apple Music's TTML for this track, fetched by the host's Apple Music provider.
export function createLyricsResolver({ loadYouTubeLyricsText, appleTtml = '' }) {
  async function resolveYoutubeLyrics(track = {}) {
    if (!track.id) return { status: 'unavailable', mode: '', lines: [], source: 'youtube' };

    try {
      const lines = String(await loadYouTubeLyricsText(track.id) || '')
        .split(/\r?\n/)
        .map((text) => ({ text: text.trim() }))
        .filter((line) => line.text);

      if (lines.length) {
        return {
          status: 'ready',
          mode: 'unsynced',
          lines,
          source: 'youtube'
        };
      }
    } catch {
      // Missing lyrics tabs and region-restricted lyrics are normal for this fallback.
    }

    return { status: 'unavailable', mode: '', lines: [], source: 'youtube' };
  }

  return async function resolveLyrics(track = {}, provider = 'bini') {
    if (provider === 'amlyrics') return resolveLyricsChain(resolveLyrics, track, lyricsProviders.slice(0, 3));
    if (provider === 'youtube') return resolveYoutubeLyrics(track);
    if (provider === 'lrclib') return resolveLrclibLyrics(track);
    if (provider === 'apple') {
      try {
        if (appleTtml) return normalizeAmLyricsLines(parseAmLyricsTtml(appleTtml), 'apple');
      } catch {
        // Malformed TTML lets the configured chain continue.
      }
      return { status: 'unavailable', mode: '', lines: [], source: 'apple' };
    }
    const metadata = lyricsMetadata(track);
    if (metadata) {
      try {
        let lines = [];
        if (provider === 'bini') lines = await resolveBiniLyrics(metadata);
        else if (provider === 'betterlyrics')
          lines = await resolveBetterLyrics(metadata, { fetchWithTimeout, parseTtml: parseAmLyricsTtml });
        else if (provider === 'lyricsplus') lines = await resolveLyricsPlusLyrics(metadata);
        return normalizeAmLyricsLines(lines || [], provider);
      } catch {
        // A failed provider lets the configured chain continue.
      }
    }
    return { status: 'unavailable', mode: '', lines: [], source: provider };
  };
}

export const lyricsProviders = Object.freeze(['bini', 'betterlyrics', 'lyricsplus', 'lrclib', 'youtube', 'apple']);

export async function resolveLyricsChain(resolveLyrics, track = {}, providers = lyricsProviders) {
  const order = Array.isArray(providers) ? [...new Set(providers)].filter((id) => lyricsProviders.includes(id)) : lyricsProviders;
  // Plain text from an early provider must not hide timed lyrics from a later one;
  // it is kept only as the answer when nobody further down has them synced.
  let plain = null;
  for (const provider of order) {
    try {
      const data = await resolveLyrics(track, provider);
      if (data?.status !== 'ready' || !(data.lines?.length > 0)) continue;
      if (data.mode === 'synced') return data;
      plain ||= data;
    } catch {
      // A provider failure is handled the same as an unavailable result.
    }
  }
  return plain || { status: 'unavailable', mode: '', lines: [], source: '' };
}
