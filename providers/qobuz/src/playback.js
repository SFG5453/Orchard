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

import { openQobuzDirectSource, readQobuzDirectRange } from './direct.js';

const SOURCE_LIMIT = 12;
const SOURCE_MAX_AGE_MS = 6 * 60 * 60_000;

function defaultRandomUuid() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  throw new TypeError('Qobuz playback requires a host randomUuid() function');
}

export function createQobuzPlayback({
  client,
  fetchImpl = fetch,
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

  async function refreshSource(source) {
    if (!source.refreshPromise) {
      source.refreshPromise = client.streamingInfo(source.trackId, source.quality)
        .then((info) => { source.direct.url = info.url; })
        .finally(() => { source.refreshPromise = null; });
    }
    return source.refreshPromise;
  }

  async function resolveStream(match, quality) {
    const info = await client.streamingInfo(match.qobuzTrackId, quality);
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

  /** Inclusive byte range of the track's FLAC file. */
  async function readRange(playbackId, { start = 0, end } = {}, singleChunk = false) {
    const source = sources.get(playbackId);
    if (!source) throw new Error('Qobuz playback source expired');
    touch(playbackId, source);
    const rangeEnd = end === undefined ? source.direct.totalLength - 1 : Number(end);
    return readQobuzDirectRange(source, Number(start), rangeEnd, { fetchImpl, refresh: refreshSource, singleChunk });
  }

  // Streaming reads yield at most one chunk so ready audio is not held behind later bytes.
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
