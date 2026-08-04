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
import { createBrowserMusicFetch } from '../electron/auth/browserMusicApi.js';

test('browser player requests use the authenticated YouTube Music origin and identity', async () => {
  let captured;
  const browserFetch = createBrowserMusicFetch({
    authState: {
      browser: {
        cookie: 'SAPISID=secret',
        visitorData: 'visitor',
        dataSyncId: 'page-id',
        accountIndex: 2
      }
    },
    fetchImpl: async (input, init) => {
      captured = { url: String(input), init };
      return new Response('{}');
    },
    youtubeMusicClientUserAgent: 'Orchard test agent',
    youtubeMusicClientVersion: '1.test',
    youtubeMusicOrigin: 'https://music.youtube.com'
  });

  const input = new Request('https://www.youtube.com/youtubei/v1/player?prettyPrint=false', {
    method: 'POST'
  });
  await browserFetch(input, {
    headers: { 'Content-Type': 'application/json', 'X-Goog-PageId': 'page-id' },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB', clientVersion: 'old' } },
      videoId: 'private-upload'
    })
  });

  const headers = new Headers(captured.init.headers);
  const body = JSON.parse(captured.init.body);
  assert.equal(captured.url, 'https://music.youtube.com/youtubei/v1/player?prettyPrint=false');
  assert.equal(captured.init.method, 'POST');
  assert.match(headers.get('Authorization'), /^SAPISIDHASH \d+_[a-f0-9]{40}$/);
  assert.equal(headers.get('Cookie'), 'SAPISID=secret; SOCS=CAI; PREF=f2=8000000&hl=en');
  assert.equal(headers.get('X-Origin'), 'https://music.youtube.com');
  assert.equal(headers.get('X-Goog-AuthUser'), '2');
  assert.equal(headers.get('X-Goog-PageId'), 'page-id');
  assert.equal(body.context.client.clientName, 'WEB_REMIX');
  assert.equal(body.context.client.clientVersion, '1.test');
  assert.equal(body.context.client.visitorData, 'visitor');
  assert.equal(body.context.user.onBehalfOfUser, 'page-id');
});

test('non-API requests pass through unchanged', async () => {
  const calls = [];
  const browserFetch = createBrowserMusicFetch({
    authState: { browser: { cookie: 'SAPISID=secret' } },
    fetchImpl: async (input, init) => {
      calls.push({ input, init });
      return new Response('{}');
    },
    youtubeMusicClientUserAgent: 'agent',
    youtubeMusicClientVersion: '1.test',
    youtubeMusicOrigin: 'https://music.youtube.com'
  });
  const init = { method: 'GET' };

  await browserFetch('https://www.youtube.com/player.js', init);

  assert.equal(calls[0].input, 'https://www.youtube.com/player.js');
  assert.equal(calls[0].init, init);
});

test('browser next requests use authenticated YouTube Music origin', async () => {
  let captured;
  const browserFetch = createBrowserMusicFetch({
    authState: {
      browser: {
        cookie: 'SAPISID=secret',
        visitorData: 'visitor',
        dataSyncId: 'page-id'
      }
    },
    fetchImpl: async (input, init) => {
      captured = { url: String(input), init };
      return new Response('{}');
    },
    youtubeMusicClientUserAgent: 'Orchard test agent',
    youtubeMusicClientVersion: '1.test',
    youtubeMusicOrigin: 'https://music.youtube.com'
  });

  await browserFetch('https://www.youtube.com/youtubei/v1/next?prettyPrint=false&alt=json', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB', clientVersion: 'old' } },
      videoId: 'test123'
    })
  });

  const headers = new Headers(captured.init.headers);
  assert.equal(captured.url, 'https://music.youtube.com/youtubei/v1/next?prettyPrint=false&alt=json');
  assert.match(headers.get('Authorization'), /^SAPISIDHASH \d+_[a-f0-9]{40}$/);
  assert.ok(headers.get('Cookie').includes('SAPISID=secret'));
});
