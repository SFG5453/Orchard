// Coordinates lyrics providers and converts their formats to the renderer-facing result shape.
import { createRequire } from 'node:module';
import { resolveBetterLyrics } from './betterLyricsProvider.js';

const require = createRequire(import.meta.url);
const { Client: LyricsClient, parseLocalLyrics } = require('lrclib-api');
const { DOMParser } = require('@xmldom/xmldom');

const lyricsClient = new LyricsClient();
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
  const seconds = Number(track.durationSeconds || 0);
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

async function fetchWithTimeout(url, options = {}, timeoutMs = amLyricsFetchTimeoutMs) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}

function amLyricsTimeMs(value, fallback = 0) {
  if (value == null || value === '') return fallback;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return fallback;
    return Number.isInteger(value) ? Math.max(0, Math.round(value)) : Math.max(0, Math.round(value * 1000));
  }

  const text = String(value).trim();
  if (!text) return fallback;
  if (/ms$/i.test(text)) return Math.max(0, Math.round(Number.parseFloat(text)));
  if (/s$/i.test(text)) return Math.max(0, Math.round(Number.parseFloat(text) * 1000));

  const parts = text.split(':').map((part) => Number.parseFloat(part));
  if (parts.some((part) => !Number.isFinite(part))) return fallback;

  if (parts.length === 3) {
    return Math.max(0, Math.round(((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000));
  }

  if (parts.length === 2) {
    return Math.max(0, Math.round(((parts[0] * 60) + parts[1]) * 1000));
  }

  const numeric = Number.parseFloat(text);
  return Number.isFinite(numeric) ? Math.max(0, Math.round(numeric * 1000)) : fallback;
}

function normalizeLyricLineText(value = '') {
  return String(value || '')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeLyricWordText(value = '') {
  return String(value || '').replace(/\s+/g, ' ');
}

function makeLyricWord(text, startMs = 0, endMs = 0) {
  const word = {
    text: normalizeLyricWordText(text),
    startTime: Math.max(0, startMs / 1000)
  };
  if (endMs > startMs) word.endTime = Math.max(0, endMs / 1000);
  return word;
}

function mergeTimedLyricWords(words = []) {
  const cleanWords = words.filter((word) => word?.text?.trim() && typeof word.startTime === 'number');
  if (!cleanWords.some((word) => /\s/.test(word.text))) return cleanWords;

  const merged = [];
  let current = null;

  function finishCurrentWord() {
    if (!current?.text) return;
    const word = {
      text: current.text,
      startTime: current.startTime
    };
    if (typeof current.endTime === 'number' && current.endTime > current.startTime) word.endTime = current.endTime;
    merged.push(word);
    current = null;
  }

  cleanWords.forEach((word) => {
    const parts = String(word.text || '').match(/\S+\s*/g) || [];
    parts.forEach((part) => {
      const text = part.trim();
      if (!text) return;

      if (!current) {
        current = {
          text: '',
          startTime: word.startTime,
          endTime: word.endTime
        };
      }

      current.text += text;
      if (typeof word.endTime === 'number' && (typeof current.endTime !== 'number' || word.endTime > current.endTime)) {
        current.endTime = word.endTime;
      }

      if (/\s$/.test(part)) finishCurrentWord();
    });
  });

  finishCurrentWord();
  return merged.length ? merged : cleanWords;
}

function normalizeTimedLyricWords(words = []) {
  return mergeTimedLyricWords(words
    .filter((word) => word?.text?.trim() && typeof word.startTime === 'number')
    .map((word) => ({
      text: word.text,
      startTime: word.startTime,
      ...(typeof word.endTime === 'number' && word.endTime > word.startTime ? { endTime: word.endTime } : {})
    })));
}

function makeLyricLine(text, startMs = 0, endMs = 0, words = [], adlibs = [], meta = {}) {
  const line = { text: normalizeLyricLineText(text) };
  if (startMs > 0 || endMs > 0) line.startTime = Math.max(0, startMs / 1000);
  if (endMs > startMs) line.endTime = Math.max(0, endMs / 1000);
  if (meta.agent) line.agent = meta.agent;
  const timedWords = normalizeTimedLyricWords(words);
  const timedAdlibs = normalizeTimedLyricWords(adlibs);
  if (timedWords.length > 1) line.words = timedWords;
  if (timedAdlibs.length) line.adlibs = timedAdlibs;
  return line;
}

function hasTimedChildSpan(span) {
  return Array.from(span.getElementsByTagName('span'))
    .some((child) => child !== span && child.getAttribute('begin'));
}

function isBackgroundLyricSpan(span) {
  let node = span;
  while (node?.getAttribute) {
    if (node.getAttribute('ttm:role') === 'x-bg') return true;
    node = node.parentNode;
  }
  return false;
}

function spanTextWithTrailingWordBreak(span) {
  const text = span.textContent || '';
  const nextText = span.nextSibling?.nodeType === 3 ? span.nextSibling.nodeValue || '' : '';
  return /\s/.test(nextText) ? `${text} ` : text;
}

function parseAmLyricsTtml(ttml = '') {
  if (!ttml.trim()) return [];

  const doc = new DOMParser().parseFromString(ttml, 'text/xml');
  if (doc.getElementsByTagName('parsererror').length) return [];

  return Array.from(doc.getElementsByTagName('p'))
    .map((node) => {
      const spans = Array.from(node.getElementsByTagName('span'));
      const timedSpans = spans
        .filter((span) => span.getAttribute('begin') && span.textContent?.trim() && !hasTimedChildSpan(span));
      const wordSpans = timedSpans
        .filter((span) => !isBackgroundLyricSpan(span))
        .map((span) => makeLyricWord(
          spanTextWithTrailingWordBreak(span),
          amLyricsTimeMs(span.getAttribute('begin')),
          amLyricsTimeMs(span.getAttribute('end'))
        ));
      const adlibSpans = timedSpans
        .filter((span) => isBackgroundLyricSpan(span))
        .map((span) => makeLyricWord(
          spanTextWithTrailingWordBreak(span),
          amLyricsTimeMs(span.getAttribute('begin')),
          amLyricsTimeMs(span.getAttribute('end'))
        ));
      const allSpans = [...wordSpans, ...adlibSpans].sort((a, b) => a.startTime - b.startTime);
      const firstTimedSpan = wordSpans[0] || allSpans[0];
      const lastTimedSpan = [...wordSpans, ...adlibSpans].reverse().find((word) => typeof word.endTime === 'number');
      const startMs = amLyricsTimeMs(node.getAttribute('begin'), Math.round((firstTimedSpan?.startTime || 0) * 1000));
      const endMs = amLyricsTimeMs(node.getAttribute('end'), Math.round((lastTimedSpan?.endTime || 0) * 1000));
      const mainText = wordSpans.map((word) => word.text).join('').trim() || node.textContent || '';
      let agentNode = node;
      let agent = '';
      while (agentNode?.getAttribute && !agent) {
        agent = agentNode.getAttribute('ttm:agent') || '';
        agentNode = agentNode.parentNode;
      }
      return makeLyricLine(mainText, startMs, endMs, wordSpans, adlibSpans, { agent });
    })
    .filter((line) => line.text || line.adlibs?.length)
    .sort((a, b) => (a.startTime || 0) - (b.startTime || 0));
}

function amLyricsPayloadItems(payload) {
  if (Array.isArray(payload?.lyrics)) return payload.lyrics;
  if (Array.isArray(payload?.data?.lyrics)) return payload.data.lyrics;
  if (Array.isArray(payload?.data)) return payload.data;
  return [];
}

function textFromAmLyricsEntry(entry = {}) {
  if (typeof entry.text === 'string' && entry.text.trim()) return entry.text;

  const syllables = Array.isArray(entry.syllabus)
    ? entry.syllabus
    : Array.isArray(entry.words) ? entry.words : [];

  const mainSyllables = syllables.filter((syllable) => !syllable?.isBackground);
  return (mainSyllables.length ? mainSyllables : syllables)
    .map((syllable) => syllable?.text || '')
    .join('')
    .trim();
}

function timedWordsFromAmLyricsEntry(entry = {}, lineStartMs = 0, lineEndMs = 0) {
  const syllables = Array.isArray(entry.syllabus)
    ? entry.syllabus
    : Array.isArray(entry.words) ? entry.words : [];

  const result = {
    words: [],
    adlibs: []
  };

  syllables
    .filter((syllable) => syllable?.text?.trim())
    .forEach((syllable) => {
      const startMs = amLyricsTimeMs(syllable.time, lineStartMs);
      const durationMs = amLyricsTimeMs(syllable.duration);
      const endMs = durationMs > 0
        ? startMs + durationMs
        : syllables.length === 1 && lineEndMs > lineStartMs ? lineEndMs : startMs;
      const word = makeLyricWord(syllable.text, startMs, endMs);
      if (syllable.isBackground) result.adlibs.push(word);
      else result.words.push(word);
    });

  return result;
}

function parseAmLyricsPayload(payload) {
  return amLyricsPayloadItems(payload)
    .map((entry) => {
      const startMs = amLyricsTimeMs(entry?.time);
      const durationMs = amLyricsTimeMs(entry?.duration);
      const explicitEndMs = amLyricsTimeMs(entry?.endTime);
      const endMs = explicitEndMs || (durationMs > 0 ? startMs + durationMs : 0);
      const { words, adlibs } = timedWordsFromAmLyricsEntry(entry, startMs, endMs);
      return makeLyricLine(textFromAmLyricsEntry(entry), startMs, endMs, words, adlibs);
    })
    .filter((line) => line.text || line.adlibs?.length)
    .sort((a, b) => (a.startTime || 0) - (b.startTime || 0));
}

function normalizeAmLyricsLines(lines = []) {
  const cleanLines = lines
    .map((line) => ({
      ...line,
      text: normalizeLyricLineText(line.text),
      words: Array.isArray(line.words)
        ? mergeTimedLyricWords(line.words.filter((word) => word?.text?.trim() && typeof word.startTime === 'number'))
        : undefined,
      adlibs: Array.isArray(line.adlibs)
        ? mergeTimedLyricWords(line.adlibs.filter((word) => word?.text?.trim() && typeof word.startTime === 'number'))
        : undefined
    }))
    .filter((line) => line.text || line.adlibs?.length);

  if (!cleanLines.length) return { status: 'unavailable', mode: '', lines: [], source: 'amlyrics' };

  const agentCounts = cleanLines.reduce((counts, line) => {
    if (line.agent) counts.set(line.agent, (counts.get(line.agent) || 0) + 1);
    return counts;
  }, new Map());
  const primaryAgent = [...agentCounts].sort((left, right) => right[1] - left[1])[0]?.[0] || '';
  if (primaryAgent && agentCounts.size > 1) {
    cleanLines.forEach((line) => { line.agentLane = line.agent && line.agent !== primaryAgent ? 'alternate' : 'primary'; });
  }

  return {
    status: 'ready',
    mode: cleanLines.some((line) => typeof line.startTime === 'number') ? 'synced' : 'unsynced',
    lines: cleanLines,
    source: 'amlyrics'
  };
}

async function resolveBiniLyrics(metadata) {
  const params = new URLSearchParams({
    track: metadata.title,
    artist: metadata.artist
  });
  if (metadata.album) params.append('album', metadata.album);
  if (metadata.durationMs) params.append('duration', Math.round(metadata.durationMs / 1000).toString());

  const response = await fetchWithTimeout(`https://lyrics-api.binimum.org/?${params.toString()}`);
  if (!response.ok) return null;

  const payload = await response.json();
  const lyricsUrl = payload?.results?.find?.((item) => item?.lyricsUrl)?.lyricsUrl;
  if (!lyricsUrl) return null;

  const lyricsResponse = await fetchWithTimeout(lyricsUrl);
  if (!lyricsResponse.ok) return null;

  return parseAmLyricsTtml(await lyricsResponse.text());
}

async function resolveLyricsPlusLyrics(metadata) {
  const params = new URLSearchParams({
    title: metadata.title,
    artist: metadata.artist
  });
  if (metadata.album) params.append('album', metadata.album);
  if (metadata.durationMs) params.append('duration', Math.round(metadata.durationMs / 1000).toString());

  for (const base of amLyricsServers) {
    try {
      const response = await fetchWithTimeout(`${base}/v2/lyrics/get?${params.toString()}`);
      if (!response.ok) continue;

      const lines = parseAmLyricsPayload(await response.json());
      if (lines.length) return lines;
    } catch {
      // Try the next LyricsPlus mirror.
    }
  }

  return [];
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

  try {
    return normalizeLyricsResult(await lyricsClient.findLyrics(query));
  } catch {
    try {
      const search = await lyricsClient.searchLyrics({
        track_name: query.track_name,
        artist_name: query.artist_name,
        duration: query.duration
      });
      const firstWithLyrics = search.find((item) => item.syncedLyrics || item.plainLyrics || item.instrumental);
      return normalizeLyricsResult(firstWithLyrics);
    } catch {
      return { status: 'unavailable', mode: '', lines: [], source: 'lrclib' };
    }
  }
}

async function resolveAmLyrics(track = {}) {
  const metadata = lyricsMetadata(track);
  if (!metadata) return { status: 'unavailable', mode: '', lines: [], source: 'amlyrics' };

  try {
    const biniLines = await resolveBiniLyrics(metadata);
    if (biniLines?.length) return normalizeAmLyricsLines(biniLines);
  } catch {
    // BiniLyrics is the fastest Apple/TTML path when available.
  }

  try {
    const betterLines = await resolveBetterLyrics(metadata, { fetchWithTimeout, parseTtml: parseAmLyricsTtml });
    if (betterLines?.length) return normalizeAmLyricsLines(betterLines);
  } catch {
    // BetterLyrics is another TTML source credited under the am-lyrics provider.
  }

  try {
    return normalizeAmLyricsLines(await resolveLyricsPlusLyrics(metadata));
  } catch {
    return { status: 'unavailable', mode: '', lines: [], source: 'amlyrics' };
  }
}

export function createLyricsResolver({ musicClientForBrowse }) {
  async function resolveYoutubeLyrics(track = {}) {
    if (!track.id) return { status: 'unavailable', mode: '', lines: [], source: 'youtube' };

    try {
      const yt = await musicClientForBrowse();
      const lyrics = await yt.music.getLyrics(track.id);
      const lines = String(lyrics?.description?.toString() || '')
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

  return async function resolveLyrics(track = {}, provider = 'amlyrics') {
    if (provider === 'amlyrics') return resolveAmLyrics(track);
    if (provider === 'youtube') return resolveYoutubeLyrics(track);
    return resolveLrclibLyrics(track);
  };
}
