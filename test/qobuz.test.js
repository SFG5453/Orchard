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
import { createCipheriv } from 'node:crypto';
import test from 'node:test';

import {
  QOBUZ_CMAF_UUIDS,
  createQobuz,
  createQobuzAuthorizationUrl,
  createQobuzClient,
  createQobuzPlayback,
  createQobuzReporter,
  decryptQobuzAudioSegment,
  exchangeQobuzAuthorizationCode,
  extractQobuzBootstrap,
  parseQobuzInitSegment,
  qobuzRequestSignature,
  selectQobuzMatch,
  normalizeQobuzAlbumQuality
} from '@orchardmusic/qobuz';
import { createPlaybackProviderCoordinator } from '../electron/providers/playbackProvider.js';
import { playbackQualityLabel } from '../src/app/playback/trackQuality.js';

function box(type, ...parts) {
  const content = Buffer.concat(parts);
  const header = Buffer.alloc(8);
  header.writeUInt32BE(header.length + content.length);
  header.write(type, 4, 'ascii');
  return Buffer.concat([header, content]);
}

test('playback provider coordinator dispatches opaque stream ids to the selected provider', async () => {
  const calls = [];
  const request = { method: 'GET' };
  const response = {};
  const coordinator = createPlaybackProviderCoordinator({
    providers: [{
      id: 'qobuz',
      proxyStream: async (...args) => calls.push(args)
    }]
  });

  await coordinator.proxyStream('qobuz', 'opaque-id', request, response);
  assert.deepEqual(calls, [['opaque-id', request, response]]);
  await assert.rejects(
    coordinator.proxyStream('missing', 'opaque-id', request, response),
    /Unknown playback provider/
  );
});

test('Qobuz core composes without Electron and keeps the adapter on its subpath', async () => {
  const calls = [];
  const qobuz = createQobuz({
    bootstrap: { get: async () => ({ appId: '123456789' }) },
    credentials: async () => ({ token: 'user-token', userId: 7 }),
    fetchImpl: async (url, options) => {
      calls.push({ url: String(url), options });
      return new Response(JSON.stringify({
        tracks: {
          items: [{
            id: 42,
            title: 'Example Song',
            duration: 180,
            performer: { name: 'Example Artist' },
            album: { title: 'Example Album' }
          }]
        }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
  });

  const match = await qobuz.matchTrack({
    title: 'Example Song',
    artists: ['Example Artist'],
    album: 'Example Album',
    durationMs: 180_000
  });
  assert.equal(match.qobuzTrackId, 42);
  assert.match(calls[0].url, /catalog\/search/);
  assert.equal(calls[0].options.headers['X-User-Auth-Token'], 'user-token');

  const core = await import('@orchardmusic/qobuz');
  const electron = await import('@orchardmusic/qobuz/electron');
  assert.equal(core.setupQobuzElectron, undefined);
  assert.equal(typeof electron.setupQobuzElectron, 'function');
  await qobuz.close();
});

test('Qobuz catalog quality helpers normalize album and track metadata', async () => {
  const calls = [];
  const client = createQobuzClient({
    bootstrap: { get: async () => ({ appId: '123456789' }) },
    credentials: async () => ({ token: 'user-token', userId: 7 }),
    fetchImpl: async (url, options = {}) => {
      const requestUrl = new URL(url);
      calls.push({ requestUrl, options });
      if (requestUrl.pathname.endsWith('/album/get')) {
        if (requestUrl.searchParams.get('album_id') === 'ez17kizzmln0b') {
          return new Response(JSON.stringify({
            id: 'ez17kizzmln0b',
            maximum_bit_depth: 16,
            maximum_sampling_rate: 44.1,
            maximum_channel_count: 2,
            streamable: false
          }), { status: 200 });
        }
        return new Response(JSON.stringify({
          audio_info: {
            maximum_bit_depth: 24,
            maximum_sampling_rate: 96,
            maximum_channel_count: 2
          },
          rights: { hires_streamable: false, streamable: true }
        }), { status: 200 });
      }
      return new Response(JSON.stringify({
        id: 42,
        maximum_bit_depth: 24,
        maximum_sampling_rate: 192,
        maximum_channel_count: 2,
        hires_streamable: true,
        streamable: false
      }), { status: 200 });
    }
  });

  assert.deepEqual(await client.albumQuality('0886445438048'), {
    albumId: '0886445438048',
    bitDepth: 24,
    sampleRate: 96_000,
    channels: 2,
    hiresStreamable: false,
    streamable: true
  });
  assert.deepEqual(await client.albumQuality('ez17kizzmln0b'), {
    albumId: 'ez17kizzmln0b',
    bitDepth: 16,
    sampleRate: 44_100,
    channels: 2,
    streamable: false
  });
  assert.deepEqual(normalizeQobuzAlbumQuality({ id: 'ez17kizzmln0b' }), {
    albumId: 'ez17kizzmln0b'
  });
  assert.deepEqual(normalizeQobuzAlbumQuality({}, '0886445438048'), {
    albumId: '0886445438048'
  });
  assert.deepEqual(await client.trackQuality(42), {
    trackId: 42,
    bitDepth: 24,
    sampleRate: 192_000,
    channels: 2,
    hiresStreamable: true,
    streamable: false
  });

  assert.equal(calls[0].requestUrl.pathname, '/api.json/0.2/album/get');
  assert.equal(calls[0].requestUrl.searchParams.get('album_id'), '0886445438048');
  assert.equal(calls[1].requestUrl.pathname, '/api.json/0.2/album/get');
  assert.equal(calls[1].requestUrl.searchParams.get('album_id'), 'ez17kizzmln0b');
  assert.equal(calls[2].requestUrl.pathname, '/api.json/0.2/track/get');
  assert.equal(calls[2].requestUrl.searchParams.get('track_id'), '42');
  assert.equal(calls[0].options.headers['X-App-Id'], '123456789');
  assert.equal(calls[0].options.headers['X-User-Auth-Token'], 'user-token');
});

test('Qobuz track quality batches at most 50 IDs and preserves unknown fields', async () => {
  const calls = [];
  const client = createQobuzClient({
    bootstrap: { get: async () => ({ appId: '123456789' }) },
    credentials: async () => ({ token: 'user-token', userId: 7 }),
    fetchImpl: async (url, options = {}) => {
      const requestUrl = new URL(url);
      const body = JSON.parse(options.body);
      calls.push({ requestUrl, options, body });
      return new Response(JSON.stringify({
        tracks: {
          items: body.tracks_id.map((id) => id === 1
            ? { id, maximum_bit_depth: 24, maximum_sampling_rate: 96, streamable: false }
            : { id })
        }
      }), { status: 200 });
    }
  });

  const qualities = await client.trackQualities(Array.from({ length: 51 }, (_, index) => index + 1));

  assert.equal(calls.length, 2);
  assert.equal(calls[0].requestUrl.pathname, '/api.json/0.2/track/getList');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers['Content-Type'], 'application/json');
  assert.equal(calls[0].options.headers['X-User-Auth-Token'], 'user-token');
  assert.equal(calls[0].body.tracks_id.length, 50);
  assert.deepEqual(calls[1].body.tracks_id, [51]);
  assert.equal(qualities.length, 51);
  assert.deepEqual(qualities[0], {
    trackId: 1,
    bitDepth: 24,
    sampleRate: 96_000,
    streamable: false
  });
  assert.deepEqual(qualities[50], { trackId: 51 });
});

test('createQobuz forwards catalog quality helpers through its service API', async () => {
  const qobuz = createQobuz({
    bootstrap: { get: async () => ({ appId: '123456789' }) },
    credentials: async () => ({ token: 'user-token', userId: 7 }),
    fetchImpl: async (url) => {
      const requestUrl = new URL(url);
      if (requestUrl.pathname.endsWith('/album/get')) {
        return new Response(JSON.stringify({ id: 'i5ato02r0jvy', audio_info: { maximum_sampling_rate: 48 } }), { status: 200 });
      }
      if (requestUrl.pathname.endsWith('/track/get')) {
        return new Response(JSON.stringify({ id: 42, maximum_sampling_rate: 44.1 }), { status: 200 });
      }
      return new Response(JSON.stringify({ tracks: [{ id: 42, maximum_sampling_rate: 44.1 }] }), { status: 200 });
    }
  });

  assert.equal(qobuz.albumQuality, qobuz.client.albumQuality);
  assert.equal(qobuz.trackQuality, qobuz.client.trackQuality);
  assert.equal(qobuz.trackQualities, qobuz.client.trackQualities);
  assert.deepEqual(await qobuz.albumQuality('i5ato02r0jvy'), { albumId: 'i5ato02r0jvy', sampleRate: 48_000 });
  assert.deepEqual(await qobuz.trackQuality(42), { trackId: 42, sampleRate: 44_100 });
  assert.deepEqual(await qobuz.trackQualities([42]), [{ trackId: 42, sampleRate: 44_100 }]);
  await qobuz.close();
});

test('Qobuz OAuth helpers let a non-Electron host own the redirect flow', async () => {
  const loginUrl = createQobuzAuthorizationUrl({
    appId: '123456789',
    redirectUrl: 'https://player.example/qobuz/callback'
  });
  assert.equal(loginUrl.searchParams.get('ext_app_id'), '123456789');
  assert.equal(loginUrl.searchParams.get('redirect_url'), 'https://player.example/qobuz/callback');

  let request;
  const credentials = await exchangeQobuzAuthorizationCode({
    code: 'authorization-code',
    web: { appId: '123456789', oauthPrivateKey: 'private-key' },
    fetchImpl: async (url, options) => {
      request = { url: new URL(url), options };
      return new Response(JSON.stringify({ token: 'user-token', user_id: 7 }), { status: 200 });
    }
  });
  assert.deepEqual(credentials, { token: 'user-token', userId: 7 });
  assert.equal(request.url.searchParams.get('code'), 'authorization-code');
  assert.equal(request.url.searchParams.get('private_key'), 'private-key');
  assert.equal(request.options.headers['X-App-Id'], '123456789');
});

test('Qobuz bootstrap derives every private value from the current bundle shape', () => {
  const encoded = Buffer.from('0123456789abcdef0123456789abcdef', 'utf8').toString('base64');
  const seed = encoded.slice(0, 30);
  const info = `${encoded.slice(30)}${'x'.repeat(14)}`;
  const extras = 'y'.repeat(30);
  const bundle = [
    'production:{api:{appId:"123456789",appSecret:"unused"',
    'authenticate({privateKey:"runtime-oauth",code:value})',
    `initialSeed("${seed}",window.utimezone.berlin)`,
    `name:"Europe/Berlin",info:"${info}",extras:"${extras}"`
  ].join(';');

  const value = extractQobuzBootstrap(bundle, '/resources/test/bundle.js');
  assert.equal(value.appId, '123456789');
  assert.equal(value.oauthPrivateKey, 'runtime-oauth');
  assert.equal(value.rngInit, '0123456789abcdef0123456789abcdef');
});

test('Qobuz signatures sort parameters and include the live initializer', () => {
  assert.equal(
    qobuzRequestSignature('fileurl', { track_id: 42, format_id: 27, intent: 'stream' }, 123, 'secret'),
    qobuzRequestSignature('fileurl', { intent: 'stream', format_id: 27, track_id: 42 }, '123', 'secret')
  );
});

test('Qobuz playback requests start a signed session and carry it to file/url', async () => {
  const calls = [];
  const rngInit = '00112233445566778899aabbccddeeff';
  const client = createQobuzClient({
    bootstrap: { get: async () => ({ appId: '123456789', rngInit }) },
    credentials: async () => ({ token: 'user-token', userId: 7 }),
    fetchImpl: async (url, options = {}) => {
      calls.push({ url: String(url), options });
      if (String(url).endsWith('/session/start')) {
        return new Response(JSON.stringify({
          session_id: 'session-id',
          expires_at: Math.floor(Date.now() / 1000) + 3600,
          infos: 'c2FsdA.aW5mbw'
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response(JSON.stringify({
        url_template: 'https://stream.example/$SEGMENT$',
        format_id: 27
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
  });

  const info = await client.streamingInfo(42, 'hires');
  assert.equal(info.format_id, 27);
  await client.streamingInfo(43, 'lossless');
  assert.equal(calls.length, 3);
  const startBody = new URLSearchParams(calls[0].options.body);
  assert.equal(calls[0].options.headers['X-User-Auth-Token'], 'user-token');
  assert.equal(
    startBody.get('request_sig'),
    qobuzRequestSignature('sessionstart', { profile: 'qbz-1' }, startBody.get('request_ts'), rngInit)
  );
  const streamUrl = new URL(calls[1].url);
  assert.equal(calls[1].options.headers['X-Session-Id'], 'session-id');
  assert.equal(streamUrl.searchParams.get('track_id'), '42');
  assert.equal(streamUrl.searchParams.get('format_id'), '27');
  assert.equal(
    streamUrl.searchParams.get('request_sig'),
    qobuzRequestSignature('fileurl', {
      format_id: 27,
      intent: 'stream',
      track_id: 42
    }, streamUrl.searchParams.get('request_ts'), rngInit)
  );
  const losslessUrl = new URL(calls[2].url);
  assert.equal(losslessUrl.searchParams.get('track_id'), '43');
  assert.equal(losslessUrl.searchParams.get('format_id'), '6');
});

test('Qobuz playback reporting starts once and reports the played media duration', async () => {
  const starts = [];
  const ends = [];
  const reporter = createQobuzReporter({
    client: {
      reportStreamingStart: async (event) => starts.push(event),
      reportStreamingEnd: async (event) => ends.push(event)
    }
  });
  const source = {
    blob: 'opaque-report-token',
    durationSeconds: 200,
    formatId: 7,
    trackContextUuid: 'context-id',
    trackId: 42
  };

  await reporter.started('playback-id', source, 2.2);
  await reporter.started('playback-id', source, 3);
  await reporter.ended('playback-id', 9.8);

  assert.equal(starts.length, 1);
  assert.equal(ends.length, 1);
  assert.equal(ends[0].blob, source.blob);
  assert.equal(ends[0].track_context_uuid, source.trackContextUuid);
  assert.equal(ends[0].duration, 7);
});

test('Qobuz ISRC matching prefers the highest-quality exact recording', () => {
  const target = {
    title: 'Example Song', artists: ['Example Artist'], album: 'Example', durationMs: 180_000,
    isrc: 'USABC1200001', explicit: false
  };
  const match = selectQobuzMatch(target, [
    { id: 1, title: 'Example Song', isrc: target.isrc, duration: 180, performer: { name: 'Example Artist' }, maximum_bit_depth: 16, maximum_sampling_rate: 44.1 },
    { id: 2, title: 'Example Song', isrc: target.isrc, duration: 180, performer: { name: 'Example Artist' }, hires: true, maximum_bit_depth: 24, maximum_sampling_rate: 192 }
  ], 'isrc');
  assert.equal(match.qobuzTrackId, 2);
  assert.equal(match.method, 'isrc');
  assert.equal(match.confidence, 1);
});

test('Qobuz metadata matching rejects a live substitute', () => {
  const target = {
    title: 'Example Song', artists: ['Example Artist'], album: 'Example', durationMs: 180_000,
    isrc: '', explicit: false
  };
  const match = selectQobuzMatch(target, [
    { id: 3, title: 'Example Song (Live)', duration: 180, performer: { name: 'Example Artist' }, album: { title: 'Example' } }
  ]);
  assert.equal(match, null);
});

test('Qobuz metadata matching rejects a version marker found only in the album title', () => {
  const target = {
    title: 'Example Song', artists: ['Example Artist'], album: 'Example', durationMs: 180_000,
    isrc: '', explicit: false
  };
  const match = selectQobuzMatch(target, [
    { id: 31, title: 'Example Song', duration: 180, performer: { name: 'Example Artist' }, album: { title: 'Example (Live)' } }
  ]);
  assert.equal(match, null);
});

test('Qobuz album fallback accepts a strongly identified collaboration credit', () => {
  const target = {
    title: 'Example Song', artists: ['Example Artist'], album: 'Example Album', durationMs: 180_000,
    isrc: '', explicit: false
  };
  const match = selectQobuzMatch(target, [
    {
      id: 4,
      title: 'Example Song',
      duration: 180,
      performer: { name: 'Example Artist and Guest Singer' },
      album: { title: 'Example Album' }
    }
  ], 'album-title');
  assert.equal(match.qobuzTrackId, 4);
  assert.equal(match.method, 'album-title');
});

test('Qobuz CMAF descriptors become a raw seekable FLAC byte stream', () => {
  const flac = Buffer.alloc(42);
  flac.write('fLaC');
  flac[4] = 0;
  flac.writeUIntBE(34, 5, 3);
  flac.writeUInt16BE(4096, 8);
  flac.writeUInt16BE(4096, 10);
  const packed = (96000n << 44n) | (1n << 41n) | (23n << 36n) | 480000n;
  flac.writeBigUInt64BE(packed, 18);
  const initPayload = Buffer.concat([
    Buffer.alloc(26),
    Buffer.from([0, flac.length]),
    flac,
    Buffer.from([0, 0, 1]),
    Buffer.from([0, 0, 0, 5, 0, 0, 0, 10])
  ]);
  const init = box('uuid', Buffer.from(QOBUZ_CMAF_UUIDS.init, 'hex'), initPayload);
  const parsedInit = parseQobuzInitSegment(init);
  assert.equal(parsedInit.bitDepth, 24);
  assert.equal(parsedInit.sampleRate, 96000);
  assert.equal(parsedInit.channels, 2);
  assert.equal(parsedInit.totalLength, 69);
  assert.equal(parsedInit.flacHeader[4] & 0x80, 0);
  assert.equal(parsedInit.flacHeader[42], 0x83);
  assert.equal(parsedInit.flacHeader.readUIntBE(43, 3), 18);
  assert.equal(parsedInit.flacHeader.readBigUInt64BE(46), 0n);
  assert.equal(parsedInit.flacHeader.readBigUInt64BE(54), 0n);
  assert.equal(parsedInit.flacHeader.readUInt16BE(62), 4096);
  assert.deepEqual(parsedInit.seekPoints, [{ sampleNumber: 0, streamOffset: 0, frameSamples: 4096 }]);

  const key = Buffer.from('00112233445566778899aabbccddeeff', 'hex');
  const shortIv = Buffer.from('0123456789abcdef', 'hex');
  const iv = Buffer.concat([shortIv, Buffer.alloc(8)]);
  const plaintext = Buffer.from('frame');
  const cipher = createCipheriv('aes-128-ctr', key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const descriptorPayload = Buffer.alloc(28);
  descriptorPayload.writeUInt32BE(60, 4);
  descriptorPayload[8] = 8;
  descriptorPayload.writeUIntBE(1, 9, 3);
  descriptorPayload.writeUInt32BE(encrypted.length, 12);
  descriptorPayload.writeUInt16BE(1, 18);
  shortIv.copy(descriptorPayload, 20);
  const audio = Buffer.concat([
    box('uuid', Buffer.from(QOBUZ_CMAF_UUIDS.segment, 'hex'), descriptorPayload),
    box('mdat', encrypted)
  ]);
  assert.deepEqual(decryptQobuzAudioSegment(audio, key), plaintext);
});

test('Qobuz byte ranges fetch only the audio segment containing the requested seek', async () => {
  const flac = Buffer.alloc(42);
  flac.write('fLaC');
  flac.writeUIntBE(34, 5, 3);
  flac.writeUInt16BE(4096, 8);
  flac.writeUInt16BE(4096, 10);
  const packed = (44100n << 44n) | (1n << 41n) | (15n << 36n) | 30n;
  flac.writeBigUInt64BE(packed, 18);
  const initPayload = Buffer.concat([
    Buffer.alloc(26),
    Buffer.from([0, flac.length]),
    flac,
    Buffer.from([0, 0, 3]),
    Buffer.from([
      0, 0, 0, 5, 0, 0, 0, 10,
      0, 0, 0, 5, 0, 0, 0, 10,
      0, 0, 0, 5, 0, 0, 0, 10
    ])
  ]);
  const init = box('uuid', Buffer.from(QOBUZ_CMAF_UUIDS.init, 'hex'), initPayload);
  const parsedInit = parseQobuzInitSegment(init);
  const calls = [];
  function audioSegment(text) {
    const bytes = Buffer.from(text);
    const descriptor = Buffer.alloc(28);
    descriptor.writeUInt32BE(60, 4);
    descriptor[8] = 8;
    descriptor.writeUIntBE(1, 9, 3);
    descriptor.writeUInt32BE(bytes.length, 12);
    descriptor.writeUInt16BE(0, 18);
    return Buffer.concat([
      box('uuid', Buffer.from(QOBUZ_CMAF_UUIDS.segment, 'hex'), descriptor),
      box('mdat', bytes)
    ]);
  }
  const playback = createQobuzPlayback({
    client: {
      streamingInfo: async () => ({
        duration: 30,
        format_id: 6,
        url_template: 'https://stream.example/$SEGMENT$'
      })
    },
    fetchImpl: async (url) => {
      const number = Number(new URL(url).pathname.slice(1));
      calls.push(number);
      const bytes = number === 0 ? init : audioSegment(String(number).repeat(5));
      return new Response(bytes, { status: 200 });
    },
    reporter: { ended: async () => {}, started: async () => {} }
  });
  const source = await playback.resolveStream({ qobuzTrackId: 42, durationSeconds: 30 }, 'lossless');
  assert.equal(source.preloadMode, 'metadata');
  const chunks = [];
  const response = {
    destroyed: false,
    writableEnded: false,
    writeHead(status, headers) { this.status = status; this.headers = headers; },
    write(chunk) { chunks.push(Buffer.from(chunk)); return true; },
    end() { this.writableEnded = true; }
  };
  const start = parsedInit.flacHeader.length + 10;
  await playback.proxyStream(source.playbackId, {
    method: 'GET',
    headers: { range: `bytes=${start}-${start + 4}` }
  }, response);

  assert.equal(response.status, 206);
  assert.deepEqual(Buffer.concat(chunks), Buffer.from('33333'));
  assert.deepEqual(calls, [0, 3]);
});

test('Qobuz source quality reports the actual master properties', () => {
  assert.equal(playbackQualityLabel({
    playbackSource: 'qobuz', hires: true, bitDepth: 24, sampleRate: 96000
  }), 'Qobuz Hi-Res · 24-bit / 96 kHz');
  assert.equal(playbackQualityLabel({ playbackSource: 'youtube', bitrate: 256000 }), '');
});
