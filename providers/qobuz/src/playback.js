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

import { concatBytes } from './bytes.js';
import { queryParams } from './query.js';
import {
  decryptQobuzAudioSegment,
  deriveQobuzSessionKey,
  parseQobuzInitSegment,
  unwrapQobuzContentKey
} from './cmaf.js';
import { openQobuzDirectSource, readQobuzDirectRange } from './direct.js';

const SOURCE_LIMIT = 12;
const SEGMENT_CACHE_LIMIT = 4;
const RANGE_CONCURRENCY = 4;
const SOURCE_MAX_AGE_MS = 6 * 60 * 60_000;

function streamExpiry(info) {
  const params = queryParams(String(info.url_template || '').replace('$SEGMENT$', '0'));
  for (const key of ['Expires', 'expires', 'exp', 'e']) {
    const value = Number(params[key]);
    if (value > 1_000_000_000) return value * (value < 10_000_000_000 ? 1000 : 1);
  }
  return Number(info.session?.expiresAt || 0);
}

function contentKey(info, crypto) {
  if (!info.key) return null;
  const sessionKey = deriveQobuzSessionKey(info.session?.infos, info.rngInit, crypto);
  return unwrapQobuzContentKey(sessionKey, info.key, crypto);
}

function defaultRandomUuid() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  throw new TypeError('Qobuz playback requires a host randomUuid() function');
}

export function createQobuzPlayback({
  client,
  crypto,
  fetchImpl = fetch,
  logger = console,
  randomUuid = defaultRandomUuid,
  reporter
} = {}) {
  const sources = new Map();

  function touch(playbackId, source) {
    source.lastAccess = Date.now();
    sources.delete(playbackId);
    sources.set(playbackId, source);
  }

  function prune() {
    const oldestAllowed = Date.now() - SOURCE_MAX_AGE_MS;
    for (const [playbackId, source] of sources) {
      if (source.lastAccess < oldestAllowed) sources.delete(playbackId);
    }
    while (sources.size > SOURCE_LIMIT) sources.delete(sources.keys().next().value);
  }

  async function fetchBytes(url, label) {
    const response = await fetchImpl(url, { headers: { 'Accept': '*/*' } });
    if (!response.ok) {
      const error = new Error(`${label} failed (${response.status})`);
      error.status = response.status;
      throw error;
    }
    return new Uint8Array(await response.arrayBuffer());
  }

  async function refreshSource(source) {
    if (!source.refreshPromise) {
      source.refreshPromise = (async () => {
        const info = await client.streamingInfo(source.trackId, source.quality);
        if (source.direct) {
          source.direct.url = info.url;
          return;
        }
        source.urlTemplate = info.url_template;
        source.contentKey = contentKey(info, crypto);
        source.expiresAt = streamExpiry(info);
        source.blob = String(info.blob || source.blob || '');
      })().finally(() => { source.refreshPromise = null; });
    }
    return source.refreshPromise;
  }

  async function fetchSegment(source, number, retry = true) {
    try {
      const raw = await fetchBytes(source.urlTemplate.replace('$SEGMENT$', String(number)), `Qobuz segment ${number}`);
      const decrypted = decryptQobuzAudioSegment(raw, source.contentKey, crypto);
      const declared = source.init.segmentTable[number - 1]?.byteLength;
      if (declared && decrypted.length !== declared) {
        logger.warn?.(`Qobuz segment ${number} declared ${declared} bytes but produced ${decrypted.length}`);
      }
      source.segmentCache.set(number, decrypted);
      while (source.segmentCache.size > SEGMENT_CACHE_LIMIT) {
        source.segmentCache.delete(source.segmentCache.keys().next().value);
      }
      return decrypted;
    } catch (error) {
      if (retry && [401, 403, 404, 410].includes(Number(error.status))) {
        await refreshSource(source);
        return fetchSegment(source, number, false);
      }
      throw error;
    }
  }

  async function segment(source, number) {
    const cached = source.segmentCache.get(number);
    if (cached) {
      source.segmentCache.delete(number);
      source.segmentCache.set(number, cached);
      return cached;
    }
    if (source.segmentPromises.has(number)) return source.segmentPromises.get(number);
    const pending = fetchSegment(source, number).finally(() => { source.segmentPromises.delete(number); });
    source.segmentPromises.set(number, pending);
    return pending;
  }

  async function resolveDirectStream(match, quality, info) {
    const direct = await openQobuzDirectSource(info, { fetchImpl });
    const playbackId = randomUuid();
    const source = {
      playbackId,
      trackId: match.qobuzTrackId,
      quality,
      formatId: Number(info.format_id || 0),
      durationSeconds: Number(info.duration || match.durationSeconds || 0),
      blob: String(info.blob || ''),
      trackContextUuid: randomUuid(),
      direct,
      lastAccess: Date.now()
    };
    sources.set(playbackId, source);
    prune();
    return {
      provider: 'qobuz', playbackId, codec: 'flac', mimeType: 'audio/flac',
      bitDepth: direct.bitDepth || match.bitDepth || undefined,
      sampleRate: direct.sampleRate || match.sampleRate || undefined,
      durationSeconds: source.durationSeconds, formatId: source.formatId,
      totalBytes: direct.totalLength
    };
  }

  async function resolveStream(match, quality) {
    const info = await client.streamingInfo(match.qobuzTrackId, quality);
    if (info.direct) return resolveDirectStream(match, quality, info);
    const initBytes = await fetchBytes(info.url_template.replace('$SEGMENT$', '0'), 'Qobuz init segment');
    const init = parseQobuzInitSegment(initBytes);
    const playbackId = randomUuid();
    const source = {
      playbackId,
      trackId: match.qobuzTrackId,
      quality,
      formatId: Number(info.format_id || 0),
      durationSeconds: Number(info.duration || match.durationSeconds || 0),
      blob: String(info.blob || ''),
      trackContextUuid: randomUuid(),
      urlTemplate: info.url_template,
      contentKey: contentKey(info, crypto),
      expiresAt: streamExpiry(info),
      init,
      segmentCache: new Map(),
      segmentPromises: new Map(),
      lastAccess: Date.now()
    };
    sources.set(playbackId, source);
    prune();
    return {
      provider: 'qobuz', playbackId, codec: 'flac', mimeType: 'audio/flac',
      bitDepth: init.bitDepth || Number(info.bit_depth || info.bits_depth || match.bitDepth || 0) || undefined,
      sampleRate: init.sampleRate || Number(info.sampling_rate || match.sampleRate || 0) || undefined,
      channels: init.channels, expiresAt: source.expiresAt, durationSeconds: source.durationSeconds,
      formatId: source.formatId, segmentCount: init.segmentTable.length,
      seekPointCount: init.seekPoints.length, tableSamples: init.tableSamples,
      totalSamples: init.totalSamples, totalBytes: init.totalLength
    };
  }

  /**
   * Returns an inclusive byte range from the logical seekable FLAC stream.
   * The Qt host can expose this through QIODevice without an HTTP loopback
   * server or Node stream objects.
   */
  async function readRange(playbackId, { start = 0, end } = {}, singleSegment = false) {
    const source = sources.get(playbackId);
    if (!source) throw new Error('Qobuz playback source expired');
    touch(playbackId, source);
    if (source.direct) {
      const rangeEnd = end === undefined ? source.direct.totalLength - 1 : Number(end);
      return readQobuzDirectRange(source, Number(start), rangeEnd,
        { fetchImpl, refresh: refreshSource, singleChunk: singleSegment });
    }
    const totalLength = source.init.totalLength;
    const rangeStart = Number(start);
    const rangeEnd = end === undefined ? totalLength - 1 : Number(end);
    if (!Number.isSafeInteger(rangeStart) || !Number.isSafeInteger(rangeEnd) ||
        rangeStart < 0 || rangeStart >= totalLength || rangeEnd < rangeStart) {
      throw new RangeError(`Invalid Qobuz byte range for ${totalLength} byte stream`);
    }
    let boundedEnd = Math.min(rangeEnd, totalLength - 1);
    const headerEnd = source.init.flacHeader.length - 1;
    const table = source.init.segmentTable;
    let first = 0, limit = table.length;
    while (first < limit) {
      const middle = Math.floor((first + limit) / 2);
      const entry = table[middle];
      if (headerEnd + 1 + entry.byteOffset + entry.byteLength <= rangeStart) first = middle + 1;
      else limit = middle;
    }
    if (singleSegment) {
      // Keep upcoming bytes in flight while yielding the first ready piece.
      for (let index = first; index < Math.min(table.length, first + RANGE_CONCURRENCY); index++) {
        if (source.init.flacHeader.length + table[index].byteOffset > boundedEnd) break;
        void segment(source, index + 1).catch(() => {});
      }
      const segmentEnd = rangeStart <= headerEnd ? headerEnd :
        headerEnd + table[first].byteOffset + table[first].byteLength;
      boundedEnd = Math.min(boundedEnd, segmentEnd);
    }
    const chunks = [];
    if (rangeStart <= headerEnd) {
      chunks.push(source.init.flacHeader.subarray(rangeStart, Math.min(boundedEnd, headerEnd) + 1));
    }
    // Fetch only the requested span, in bounded batches, and assemble in stream order.
    for (let index = first; index < table.length;) {
      const pending = [];
      while (index < table.length && pending.length < RANGE_CONCURRENCY) {
        const entry = table[index];
        const logicalStart = source.init.flacHeader.length + entry.byteOffset;
        if (boundedEnd < logicalStart) break;
        const number = ++index;
        pending.push(segment(source, number).then((segmentBytes) => {
          const sliceStart = Math.max(0, rangeStart - logicalStart);
          const sliceEnd = Math.min(segmentBytes.length, boundedEnd - logicalStart + 1);
          return segmentBytes.subarray(sliceStart, sliceEnd);
        }));
      }
      if (!pending.length) break;
      chunks.push(...await Promise.all(pending));
    }
    return {
      bytes: concatBytes(chunks),
      start: rangeStart,
      end: boundedEnd,
      totalLength,
      mimeType: 'audio/flac'
    };
  }

  // Streaming reads yield metadata or one segment while later audio loads.
  function readChunk(playbackId, range) {
    return readRange(playbackId, range, true);
  }

  async function playbackStarted(playbackId, position) {
    const source = sources.get(playbackId);
    if (source) await reporter.started(playbackId, source, position);
  }

  async function playbackEnded(playbackId, position) {
    await reporter.ended(playbackId, position);
  }

  function release(playbackId) {
    sources.delete(playbackId);
  }

  function clear() {
    sources.clear();
  }

  return { clear, playbackEnded, playbackStarted, readChunk, readRange, release, resolveStream };
}
