import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createTauriYouTubeFetch,
  normalizedTauriYouTubeCookie,
  tauriYouTubeAuthorization
} from '../src/platform/desktop/tauriAuthenticatedFetch.js';

test('normalizes secure YouTube cookies for YouTube.js and playback defaults', () => {
  const cookie = normalizedTauriYouTubeCookie('__Secure-3PAPISID=secure-value; SID=session');

  assert.match(cookie, /SAPISID=secure-value/);
  assert.match(cookie, /SOCS=CAI/);
  assert.match(cookie, /PREF=f2=8000000&hl=en/);
});

test('builds the browser-compatible YouTube Music authorization signature', async () => {
  const authorization = await tauriYouTubeAuthorization('SAPISID=test-value', 1_700_000_000);

  assert.match(authorization, /^SAPISIDHASH 1700000000_[a-f0-9]{40}$/);
});

test('rewrites YouTube.js API requests to the authenticated Music origin', async () => {
  let call;
  const fetch = createTauriYouTubeFetch({
    getSession: () => ({
      cookie: 'SAPISID=test-value',
      visitorData: 'visitor',
      dataSyncId: 'page-id',
      accountIndex: 2
    }),
    fetchImpl: async (input, init) => {
      call = { input: String(input), init };
      return new Response('{}');
    }
  });

  await fetch('https://www.youtube.com/youtubei/v1/search?alt=json', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ context: { client: { clientName: 'WEB', clientVersion: 'old' } }, query: 'flo' })
  });

  const body = JSON.parse(call.init.body);
  assert.equal(call.input, 'https://music.youtube.com/youtubei/v1/search?alt=json');
  assert.equal(call.init.headers.get('X-YouTube-Client-Name'), '67');
  assert.equal(call.init.headers.get('X-Goog-AuthUser'), '2');
  assert.equal(call.init.headers.get('X-Goog-PageId'), 'page-id');
  assert.equal(body.context.client.clientName, 'WEB_REMIX');
  assert.equal(body.context.client.visitorData, 'visitor');
  assert.equal(body.context.user.onBehalfOfUser, 'page-id');
});

test('can authenticate the regular YouTube client without rewriting it as YouTube Music', async () => {
  let call;
  const fetch = createTauriYouTubeFetch({
    getSession: () => ({ cookie: 'SAPISID=test-value', accountIndex: 0 }),
    origin: 'https://www.youtube.com',
    clientName: 'WEB',
    clientHeaderName: '1',
    clientVersion: null,
    fetchImpl: async (input, init) => {
      call = { input: String(input), init };
      return new Response('{}');
    }
  });

  await fetch('https://www.youtube.com/youtubei/v1/browse?prettyPrint=false', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-YouTube-Client-Version': 'web-version' },
    body: JSON.stringify({ context: { client: { clientName: 'WEB', clientVersion: 'web-version' } } })
  });

  const body = JSON.parse(call.init.body);
  assert.equal(call.input, 'https://www.youtube.com/youtubei/v1/browse?prettyPrint=false');
  assert.equal(call.init.headers.get('Origin'), 'https://www.youtube.com');
  assert.equal(call.init.headers.get('X-YouTube-Client-Name'), '1');
  assert.equal(call.init.headers.get('X-YouTube-Client-Version'), 'web-version');
  assert.equal(body.context.client.clientName, 'WEB');
  assert.equal(body.context.client.clientVersion, 'web-version');
});
