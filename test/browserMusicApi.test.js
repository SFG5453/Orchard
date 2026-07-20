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
  assert.equal(headers.get('X-Goog-AuthUser'), null);
  assert.equal(headers.get('X-Goog-PageId'), null);
  assert.equal(body.context.client.clientName, 'WEB_REMIX');
  assert.equal(body.context.client.clientVersion, '1.test');
  assert.equal(body.context.client.visitorData, 'visitor');
  assert.equal(body.context.user.onBehalfOfUser, 'page-id');
});

test('non-player requests pass through unchanged', async () => {
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
