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

const SOURCE_LIMIT = 12;
const SEGMENT_CACHE_LIMIT = 4;
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
    const info = await client.streamingInfo(source.trackId, source.quality);
    source.urlTemplate = info.url_template;
    source.contentKey = contentKey(info, crypto);
    source.expiresAt = streamExpiry(info);
    source.blob = String(info.blob || source.blob || '');
  }

  async function segment(source, number, { retry = true } = {}) {
    const cached = source.segmentCache.get(number);
    if (cached) {
      source.segmentCache.delete(number);
      source.segmentCache.set(number, cached);
      return cached;
    }
    if (source.segmentPromises.has(number)) return source.segmentPromises.get(number);
    const pending = (async () => {
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
          source.segmentPromises.delete(number);
          await refreshSource(source);
          return segment(source, number, { retry: false });
        }
        throw error;
      } finally {
        source.segmentPromises.delete(number);
      }
    })();
    source.segmentPromises.set(number, pending);
    return pending;
  }

  async function resolveStream(match, quality) {
    const info = await client.streamingInfo(match.qobuzTrackId, quality);
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
  async function readRange(playbackId, { start = 0, end } = {}) {
    const source = sources.get(playbackId);
    if (!source) throw new Error('Qobuz playback source expired');
    touch(playbackId, source);
    const totalLength = source.init.totalLength;
    const rangeStart = Number(start);
    const rangeEnd = end === undefined ? totalLength - 1 : Number(end);
    if (!Number.isSafeInteger(rangeStart) || !Number.isSafeInteger(rangeEnd) ||
        rangeStart < 0 || rangeStart >= totalLength || rangeEnd < rangeStart) {
      throw new RangeError(`Invalid Qobuz byte range for ${totalLength} byte stream`);
    }
    const boundedEnd = Math.min(rangeEnd, totalLength - 1);
    const chunks = [];
    const headerEnd = source.init.flacHeader.length - 1;
    if (rangeStart <= headerEnd) {
      chunks.push(source.init.flacHeader.subarray(rangeStart, Math.min(boundedEnd, headerEnd) + 1));
    }
    for (let index = 0; index < source.init.segmentTable.length; index += 1) {
      const entry = source.init.segmentTable[index];
      const logicalStart = source.init.flacHeader.length + entry.byteOffset;
      const logicalEnd = logicalStart + entry.byteLength - 1;
      if (boundedEnd < logicalStart) break;
      if (rangeStart > logicalEnd) continue;
      const segmentBytes = await segment(source, index + 1);
      const sliceStart = Math.max(0, rangeStart - logicalStart);
      const sliceEnd = Math.min(segmentBytes.length, boundedEnd - logicalStart + 1);
      chunks.push(segmentBytes.subarray(sliceStart, sliceEnd));
    }
    return {
      bytes: concatBytes(chunks),
      start: rangeStart,
      end: boundedEnd,
      totalLength,
      mimeType: 'audio/flac'
    };
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

  return { clear, playbackEnded, playbackStarted, readRange, release, resolveStream };
}

