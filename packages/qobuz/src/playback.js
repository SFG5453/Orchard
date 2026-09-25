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

import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
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
  try {
    const url = new URL(String(info.url_template || '').replace('$SEGMENT$', '0'));
    for (const key of ['Expires', 'expires', 'exp', 'e']) {
      const value = Number(url.searchParams.get(key));
      if (value > 1_000_000_000) return value * (value < 10_000_000_000 ? 1000 : 1);
    }
  } catch {}
  return Number(info.session?.expiresAt || 0);
}

function contentKey(info) {
  if (!info.key) return null;
  const sessionKey = deriveQobuzSessionKey(info.session?.infos, info.rngInit);
  return unwrapQobuzContentKey(sessionKey, info.key);
}

function parseRange(value, totalLength) {
  if (!value) return { start: 0, end: totalLength - 1, partial: false };
  const match = String(value).match(/^bytes=(\d*)-(\d*)$/);
  if (!match || (!match[1] && !match[2])) return null;
  let start;
  let end;
  if (!match[1]) {
    const suffix = Number(match[2]);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return null;
    start = Math.max(0, totalLength - suffix);
    end = totalLength - 1;
  } else {
    start = Number(match[1]);
    end = match[2] ? Number(match[2]) : totalLength - 1;
  }
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start >= totalLength || end < start) return null;
  return { start, end: Math.min(end, totalLength - 1), partial: true };
}

async function writeChunk(response, chunk) {
  if (!chunk.length || response.destroyed || response.writableEnded) return;
  if (!response.write(chunk)) await once(response, 'drain');
}

export function createQobuzPlayback({ client, fetchImpl = fetch, logger = console, reporter } = {}) {
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
    return Buffer.from(await response.arrayBuffer());
  }

  async function refreshSource(source) {
    const info = await client.streamingInfo(source.trackId, source.quality);
    source.urlTemplate = info.url_template;
    source.contentKey = contentKey(info);
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
        const decrypted = decryptQobuzAudioSegment(raw, source.contentKey);
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
    const playbackId = randomUUID();
    const source = {
      playbackId,
      trackId: match.qobuzTrackId,
      quality,
      formatId: Number(info.format_id || 0),
      durationSeconds: Number(info.duration || match.durationSeconds || 0),
      blob: String(info.blob || ''),
      trackContextUuid: randomUUID(),
      urlTemplate: info.url_template,
      contentKey: contentKey(info),
      expiresAt: streamExpiry(info),
      init,
      segmentCache: new Map(),
      segmentPromises: new Map(),
      mediaRangesLogged: 0,
      lastAccess: Date.now()
    };
    sources.set(playbackId, source);
    prune();
    return {
      provider: 'qobuz',
      playbackId,
      codec: 'flac',
      mimeType: 'audio/flac',
      preloadMode: 'metadata',
      bitDepth: init.bitDepth || Number(info.bit_depth || info.bits_depth || match.bitDepth || 0) || undefined,
      sampleRate: init.sampleRate || Number(info.sampling_rate || match.sampleRate || 0) || undefined,
      channels: init.channels,
      expiresAt: source.expiresAt,
      durationSeconds: source.durationSeconds,
      formatId: source.formatId,
      segmentCount: init.segmentTable.length,
      seekPointCount: init.seekPoints.length,
      tableSamples: init.tableSamples,
      totalSamples: init.totalSamples,
      totalBytes: init.totalLength
    };
  }

  async function proxyStream(playbackId, request, response) {
    const source = sources.get(playbackId);
    if (!source) {
      response.writeHead(404, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(JSON.stringify({ error: 'Qobuz playback source expired' }));
      return;
    }
    touch(playbackId, source);
    const cors = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': 'Range, Content-Type',
      'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
      'Access-Control-Expose-Headers': 'Accept-Ranges, Content-Length, Content-Range',
      'Cache-Control': 'no-store, private',
      'Accept-Ranges': 'bytes',
      'Content-Type': 'audio/flac'
    };
    if (request.method === 'OPTIONS') {
      response.writeHead(204, cors);
      response.end();
      return;
    }
    if (!['GET', 'HEAD'].includes(request.method)) {
      response.writeHead(405, cors);
      response.end();
      return;
    }
    const range = parseRange(request.headers.range, source.init.totalLength);
    if (!range) {
      response.writeHead(416, { ...cors, 'Content-Range': `bytes */${source.init.totalLength}` });
      response.end();
      return;
    }
    const fetchDestination = String(request.headers['sec-fetch-dest'] || '');
    if (['audio', 'video'].includes(fetchDestination) && source.mediaRangesLogged < 8) {
      const firstSegment = source.init.segmentTable.findIndex((entry) =>
        source.init.flacHeader.length + entry.byteOffset + entry.byteLength > range.start
      );
      logger.info?.('[Qobuz] media range', {
        start: range.start,
        end: range.end,
        firstSegment: firstSegment < 0 ? null : firstSegment + 1
      });
      source.mediaRangesLogged += 1;
    }
    const headers = {
      ...cors,
      'Content-Length': String(range.end - range.start + 1),
      ...(range.partial ? { 'Content-Range': `bytes ${range.start}-${range.end}/${source.init.totalLength}` } : {})
    };
    response.writeHead(range.partial ? 206 : 200, headers);
    if (request.method === 'HEAD') {
      response.end();
      return;
    }

    const headerEnd = source.init.flacHeader.length - 1;
    if (range.start <= headerEnd) {
      await writeChunk(response, source.init.flacHeader.subarray(range.start, Math.min(range.end, headerEnd) + 1));
    }
    for (let index = 0; index < source.init.segmentTable.length && !response.destroyed; index += 1) {
      const entry = source.init.segmentTable[index];
      const logicalStart = source.init.flacHeader.length + entry.byteOffset;
      const logicalEnd = logicalStart + entry.byteLength - 1;
      if (range.end < logicalStart) break;
      if (range.start > logicalEnd) continue;
      const bytes = await segment(source, index + 1);
      const sliceStart = Math.max(0, range.start - logicalStart);
      const sliceEnd = Math.min(bytes.length, range.end - logicalStart + 1);
      await writeChunk(response, bytes.subarray(sliceStart, sliceEnd));
    }
    if (!response.destroyed && !response.writableEnded) response.end();
  }

  async function playbackStarted(playbackId, position) {
    const source = sources.get(playbackId);
    if (source) await reporter.started(playbackId, source, position);
  }

  async function playbackEnded(playbackId, position) {
    await reporter.ended(playbackId, position);
  }

  function clear() {
    sources.clear();
  }

  return { clear, playbackEnded, playbackStarted, proxyStream, resolveStream };
}
