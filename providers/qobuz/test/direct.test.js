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

import assert from 'node:assert/strict';
import test from 'node:test';

import { createQobuzClient, qobuzRequestSignature, verifyQobuzToken } from '../src/index.js';
import { createQobuzPlayback } from '../src/playback.js';
import { queryParams } from '../src/query.js';

const own = { token: 't', userId: 42, appId: '111222333', appSecret: 'secret' };
const noBootstrap = { get: async () => assert.fail('bootstrap fetched with user app credentials') };

function json(status, body) {
  return { ok: status < 300, status, json: async () => body, text: async () => JSON.stringify(body) };
}

test('user app credentials sign track/getFileUrl without the web bootstrap', async () => {
  const requests = [];
  const client = createQobuzClient({
    bootstrap: noBootstrap,
    credentials: async () => own,
    fetchImpl: async (url, init) => {
      requests.push({ url, init });
      return json(200, { url: 'https://cdn/file.flac', format_id: 27, bit_depth: 24, sampling_rate: 96 });
    }
  });
  const info = await client.streamingInfo(99, 'hires');
  assert.equal(info.direct, true);
  assert.equal(info.url, 'https://cdn/file.flac');
  const { url, init } = requests[0];
  assert.match(url, /\/track\/getFileUrl\?/);
  assert.equal(init.headers['X-App-Id'], own.appId);
  const params = queryParams(url);
  const expected = qobuzRequestSignature('trackgetFileUrl',
    { format_id: 27, intent: 'stream', track_id: 99 }, params.request_ts, own.appSecret);
  assert.equal(params.request_sig, expected);
});

test('preview clips are refused', async () => {
  const client = createQobuzClient({
    bootstrap: noBootstrap,
    credentials: async () => own,
    fetchImpl: async () => json(200, { url: 'https://cdn/clip.flac', sample: true })
  });
  await assert.rejects(client.streamingInfo(1, 'auto'), /preview clip/);
});

function fileServer(file, { expireFirstRead = false } = {}) {
  const ranges = [];
  let expired = expireFirstRead;
  const fetchImpl = async (url, init) => {
    const [start, end] = init.headers.Range.slice(6).split('-').map(Number);
    ranges.push({ url, start, end });
    if (url.endsWith('old.flac') && expired && start > 0) {
      expired = false;
      return { ok: false, status: 403, arrayBuffer: async () => new ArrayBuffer(0) };
    }
    const bytes = file.slice(start, end + 1);
    return {
      ok: true,
      status: 206,
      headers: { 'content-range': `bytes ${start}-${end}/${file.length}` },
      arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length)
    };
  };
  return { fetchImpl, ranges };
}

test('direct files read exact ranges and renew an expired URL', async () => {
  const file = Uint8Array.from({ length: 1000 }, (_, index) => index % 251);
  const server = fileServer(file, { expireFirstRead: true });
  let urls = 0;
  const playback = createQobuzPlayback({
    client: {
      streamingInfo: async () => ({
        direct: true, url: urls++ ? 'https://cdn/new.flac' : 'https://cdn/old.flac',
        format_id: 27, bit_depth: 24, sampling_rate: 96
      })
    },
    fetchImpl: server.fetchImpl,
    randomUuid: () => 'playback'
  });
  const source = await playback.resolveStream({ qobuzTrackId: 1 }, 'auto');
  assert.equal(source.totalBytes, file.length);
  assert.equal(source.sampleRate, 96000);
  const read = await playback.readRange(source.playbackId, { start: 100, end: 199 });
  assert.deepEqual(read.bytes, file.slice(100, 200));
  assert.equal(server.ranges.at(-1).url, 'https://cdn/new.flac');
  const tail = await playback.readChunk(source.playbackId, { start: 990, end: 5000 });
  assert.deepEqual(tail.bytes, file.slice(990));
  assert.equal(tail.end, file.length - 1);
});

test('token sign-in with app credentials checks the secret', async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    if (url.includes('/user/login')) return json(200, { user: { id: 42 }, user_auth_token: 't' });
    return json(400, { message: 'Invalid Request Signature parameter (request_sig)' });
  };
  await assert.rejects(
    verifyQobuzToken({ fetchImpl, token: 't', userId: 42, appId: own.appId, appSecret: 'bad' }),
    /app secret/
  );
  assert.match(calls[0], /app_id=111222333/);
  const ok = async (url) => json(200, url.includes('/user/login') ? { user: { id: 42 } } : { url: 'x' });
  assert.deepEqual(
    await verifyQobuzToken({ fetchImpl: ok, token: 't', userId: 42, appId: own.appId, appSecret: 's' }),
    { token: 't', userId: 42, appId: own.appId, appSecret: 's' }
  );
  await assert.rejects(verifyQobuzToken({ fetchImpl: ok, token: 't', userId: 42, appId: own.appId }), /both/);
});
