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

import { XMLParser } from 'fast-xml-parser';

const lrcTimestamp = /\[(\d+):([0-5]\d)(?:[.:](\d{1,3}))?\]/g;
const lrcMetadataLine = /^\[[a-z][a-z0-9_-]*:.*\]$/i;

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
  if (meta.key) line.key = meta.key;
  const timedWords = normalizeTimedLyricWords(words);
  const timedAdlibs = normalizeTimedLyricWords(adlibs);
  if (timedWords.length > 1) line.words = timedWords;
  if (timedAdlibs.length) line.adlibs = timedAdlibs;
  return line;
}

const ttmlParser = new XMLParser({
  preserveOrder: true,
  ignoreAttributes: false,
  attributeNamePrefix: '',
  trimValues: false,
  parseTagValue: false,
  parseAttributeValue: false,
  htmlEntities: true,
  processEntities: { maxTotalExpansions: 10000, maxExpandedLength: 1000000 }
});

function ttmlElements(nodes, tag, parent = null) {
  const result = [];
  for (let index = 0; index < nodes.length; index++) {
    const entry = nodes[index];
    const name = Object.keys(entry).find((key) => key !== ':@');
    if (!name || name.startsWith('#') || name.startsWith('?')) continue;
    const node = { name, attributes: entry[':@'] || {}, children: entry[name] || [], parent, next: nodes[index + 1] };
    if (name === tag) result.push(node);
    result.push(...ttmlElements(node.children, tag, node));
  }
  return result;
}

function ttmlText(nodes) {
  return nodes.map((entry) => {
    if (typeof entry['#text'] === 'string') return entry['#text'];
    const name = Object.keys(entry).find((key) => key !== ':@');
    return Array.isArray(entry[name]) ? ttmlText(entry[name]) : '';
  }).join('');
}

function hasTimedChildSpan(span) {
  return ttmlElements(span.children, 'span', span).some((child) => child.attributes.begin);
}

function isBackgroundLyricSpan(span) {
  for (let node = span; node; node = node.parent)
    if (node.attributes['ttm:role'] === 'x-bg') return true;
  return false;
}

function spanTextWithTrailingWordBreak(span) {
  const text = ttmlText(span.children);
  const nextText = span.next?.['#text'] || '';
  return /\s/.test(nextText) ? `${text} ` : text;
}

export function parseAmLyricsTtml(ttml = '') {
  if (!ttml.trim()) return [];

  let document;
  try {
    document = ttmlParser.parse(ttml, true);
  } catch {
    return [];
  }

  return ttmlElements(document, 'p')
    .map((node) => {
      const spans = ttmlElements(node.children, 'span', node);
      const timedSpans = spans
        .filter((span) => span.attributes.begin && ttmlText(span.children).trim() && !hasTimedChildSpan(span));
      const wordSpans = timedSpans
        .filter((span) => !isBackgroundLyricSpan(span))
        .map((span) => makeLyricWord(
          spanTextWithTrailingWordBreak(span),
          amLyricsTimeMs(span.attributes.begin),
          amLyricsTimeMs(span.attributes.end)
        ));
      const adlibSpans = timedSpans
        .filter((span) => isBackgroundLyricSpan(span))
        .map((span) => makeLyricWord(
          spanTextWithTrailingWordBreak(span),
          amLyricsTimeMs(span.attributes.begin),
          amLyricsTimeMs(span.attributes.end)
        ));
      const allSpans = [...wordSpans, ...adlibSpans].sort((a, b) => a.startTime - b.startTime);
      const firstTimedSpan = wordSpans[0] || allSpans[0];
      const lastTimedSpan = [...wordSpans, ...adlibSpans].reverse().find((word) => typeof word.endTime === 'number');
      const startMs = amLyricsTimeMs(node.attributes.begin, Math.round((firstTimedSpan?.startTime || 0) * 1000));
      const endMs = amLyricsTimeMs(node.attributes.end, Math.round((lastTimedSpan?.endTime || 0) * 1000));
      const mainText = wordSpans.map((word) => word.text).join('').trim() || ttmlText(node.children);
      let agentNode = node;
      let agent = '';
      while (agentNode && !agent) {
        agent = agentNode.attributes['ttm:agent'] || '';
        agentNode = agentNode.parent;
      }
      return makeLyricLine(mainText, startMs, endMs, wordSpans, adlibSpans, { agent, key: node.attributes['itunes:key'] || '' });
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

export function parseAmLyricsPayload(payload) {
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

export function normalizeAmLyricsLines(lines = [], source = 'bini') {
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

  if (!cleanLines.length) return { status: 'unavailable', mode: '', lines: [], source };

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
    source
  };
}

function lrcSeconds(minutes, seconds, fraction = '') {
  return Number(minutes) * 60 + Number(seconds) + (fraction ? Number(fraction) / 10 ** fraction.length : 0);
}

export function parseLocalLyrics(lyrics = '') {
  const synced = [];
  const unsynced = [];
  for (const rawLine of String(lyrics).replace(/\r\n?/g, '\n').split('\n')) {
    const line = rawLine.trim();
    if (!line || lrcMetadataLine.test(line)) continue;
    const matches = [...line.matchAll(lrcTimestamp)];
    if (!matches.length) {
      unsynced.push({ text: line });
      continue;
    }
    const text = line.replace(lrcTimestamp, '').trim();
    if (!text) continue;
    // Repeated choruses share one line with several stamps.
    for (const match of matches) synced.push({ text, startTime: lrcSeconds(match[1], match[2], match[3]) });
  }
  synced.sort((a, b) => a.startTime - b.startTime);
  return { synced: synced.length ? synced : null, unsynced };
}
