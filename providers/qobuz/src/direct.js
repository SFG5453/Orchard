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

// Plain FLAC files from track/getFileUrl, read with HTTP Range requests.
const CHUNK_LIMIT = 512 * 1024;

function header(response, name) {
  const headers = response.headers;
  return String((typeof headers?.get === 'function' ? headers.get(name) : headers?.[name]) || '');
}

function rateHz(value) {
  const rate = Number(value || 0);
  return rate > 0 && rate < 1000 ? Math.round(rate * 1000) : rate;
}

async function rangeRequest(fetchImpl, url, start, end) {
  const response = await fetchImpl(url, { headers: { 'Accept': '*/*', 'Range': `bytes=${start}-${end}` } });
  if (!response.ok) {
    const error = new Error(`Qobuz file range failed (${response.status})`);
    error.status = response.status;
    throw error;
  }
  return response;
}

export async function openQobuzDirectSource(info, { fetchImpl }) {
  const probe = await rangeRequest(fetchImpl, info.url, 0, 0);
  const totalLength = Number(header(probe, 'content-range').split('/')[1] || header(probe, 'content-length'));
  if (!Number.isSafeInteger(totalLength) || totalLength <= 1) {
    throw new Error('Qobuz file URL did not report its length');
  }
  return {
    url: info.url,
    totalLength,
    bitDepth: Number(info.bit_depth || 0) || undefined,
    sampleRate: rateHz(info.sampling_rate) || undefined
  };
}

export async function readQobuzDirectRange(source, start, end, { fetchImpl, refresh, singleChunk }) {
  const totalLength = source.direct.totalLength;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) ||
      start < 0 || start >= totalLength || end < start) {
    throw new RangeError(`Invalid Qobuz byte range for ${totalLength} byte stream`);
  }
  let boundedEnd = Math.min(end, totalLength - 1);
  if (singleChunk) boundedEnd = Math.min(boundedEnd, start + CHUNK_LIMIT - 1);
  let response;
  try {
    response = await rangeRequest(fetchImpl, source.direct.url, start, boundedEnd);
  } catch (error) {
    if (![401, 403, 404, 410].includes(Number(error.status))) throw error;
    await refresh(source);
    response = await rangeRequest(fetchImpl, source.direct.url, start, boundedEnd);
  }
  let bytes = new Uint8Array(await response.arrayBuffer());
  // A server that ignores Range returns the whole file.
  if (response.status === 200 && bytes.length === totalLength) bytes = bytes.subarray(start, boundedEnd + 1);
  return { bytes, start, end: start + bytes.length - 1, totalLength, mimeType: 'audio/flac' };
}
