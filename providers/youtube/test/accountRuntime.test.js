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


import test from 'node:test';
import assert from 'node:assert/strict';
import {
  accountSummaryFromItem,
  collectAccountRenderers
} from '../src/auth/accountSummary.js';
import { asText, bestThumbnail } from '../src/catalog/musicText.js';

test('account runtime selects and normalizes real account identity fields', () => {
  const response = {
    actions: [{ accountItemRenderer: {
      isSelected: true,
      accountName: { simpleText: 'Ada Lovelace' },
      channelHandle: { simpleText: '@ada' },
      accountPhoto: [{ url: 'small', width: 32 }, { url: 'large', width: 256 }],
      serviceEndpoint: { browseEndpoint: { browseId: 'UCabcdefghijklmnopqrstuv' } }
    } }]
  };
  const account = collectAccountRenderers(response).find((item) => item.isSelected);
  assert.deepEqual(accountSummaryFromItem(account, { asText, bestThumbnail }), {
    name: 'Ada Lovelace',
    byline: '@ada',
    thumbnail: 'large',
    channelId: 'UCabcdefghijklmnopqrstuv'
  });
});

test('account runtime tolerates missing account data', () => {
  assert.deepEqual(collectAccountRenderers(null), []);
  assert.equal(accountSummaryFromItem(null, { asText, bestThumbnail }), null);
});

test('embedded account operation returns the selected real profile', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (_url, init) => {
    assert.match(init.headers.Authorization, /^SAPISIDHASH /);
    assert.equal(init.headers['X-Youtube-Bootstrap-Logged-In'], 'true');
    assert.equal(init.headers['X-Goog-AuthUser'], '2');
    assert.equal(init.headers['X-Goog-PageId'], 'channel-id');
    assert.equal(init.headers['X-Goog-Visitor-Id'], 'visitor');
    return new Response(JSON.stringify({
      actions: [{ accountItemRenderer: {
        isSelected: true,
        accountName: { simpleText: 'Ada Lovelace' },
        accountByline: { simpleText: '@ada' },
        accountPhoto: { thumbnails: [{ url: 'avatar', width: 128 }] },
        serviceEndpoint: { browseEndpoint: { browseId: 'UCabcdefghijklmnopqrstuv' } }
      } }]
    }), { status: 200 });
  };
  try {
    await import('../src/runtime/index.js');
    assert.deepEqual(await globalThis.OrchardYouTubeProvider.invoke('account.profile', {
      session: {
        cookie: 'SAPISID=secret',
        clientVersion: '1.test',
        visitorData: 'visitor',
        accountIndex: 2,
        dataSyncId: 'channel-id'
      }
    }), {
      name: 'Ada Lovelace',
      handle: '@ada',
      avatarUrl: 'avatar',
      channelId: 'UCabcdefghijklmnopqrstuv',
      channelUrl: 'https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv'
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('account profile falls back to the web account menu', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    if (String(url).startsWith('https://music.youtube.com/')) {
      return new Response(JSON.stringify({ responseContext: {}, selectText: { runs: [{ text: 'Choose a channel' }] } }), { status: 200 });
    }
    assert.match(String(url), /www\.youtube\.com\/youtubei\/v1\/account\/account_menu/);
    assert.equal(init.headers['X-YouTube-Client-Name'], '1');
    assert.match(init.headers.Authorization, /^SAPISIDHASH /);
    return new Response(JSON.stringify({
      actions: [{ openPopupAction: { popup: { multiPageMenuRenderer: {
        header: { activeAccountHeaderRenderer: {
          accountName: { simpleText: 'Selected Channel' },
          accountPhoto: { thumbnails: [{ url: 'selected-avatar', width: 128 }] },
          channelHandle: { simpleText: '@selected' }
        } }
      } } } }]
    }), { status: 200 });
  };
  try {
    await import('../src/runtime/index.js');
    const profile = await globalThis.OrchardYouTubeProvider.invoke('account.profile', {
      session: { cookie: 'SAPISID=secret', clientVersion: '1.test' }
    });
    assert.equal(profile.name, 'Selected Channel');
    assert.equal(profile.avatarUrl, 'selected-avatar');
    assert.equal(profile.handle, '@selected');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('embedded account operation rejects HTTP and malformed responses', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response('denied', { status: 403 });
    await assert.rejects(
      globalThis.OrchardYouTubeProvider.invoke('account.profile', { session: { cookie: 'SAPISID=secret', clientVersion: '1.test' } }),
      /HTTP 403/
    );
    globalThis.fetch = async () => new Response('{broken', { status: 200 });
    await assert.rejects(
      globalThis.OrchardYouTubeProvider.invoke('account.profile', { session: { cookie: 'SAPISID=secret', clientVersion: '1.test' } }),
      /JSON/
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('browser account responses use the same parser and reject anonymous results', async () => {
  await import('../src/runtime/index.js');
  const invoke = globalThis.OrchardYouTubeProvider.invoke;
  await assert.rejects(invoke('account.parse', {}), /signed-in account/);
  const profile = await invoke('account.parse', {
    accountItemRenderer: {
      isSelected: true,
      accountName: { simpleText: 'Browser account' },
      channelHandle: { simpleText: '@browser' },
      accountPhoto: { thumbnails: [{ url: 'avatar', width: 128 }] }
    }
  });
  assert.equal(profile.name, 'Browser account');
  assert.equal(profile.handle, '@browser');
  assert.equal(profile.avatarUrl, 'avatar');
});

test('public channel lookup recovers a switched channel name and portrait', async () => {
  await import('../src/runtime/index.js');
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      assert.equal(url, 'https://www.youtube.com/@brand');
      return new Response('<meta property="og:title" content="Brand Channel"><meta property="og:image" content="https://example.com/brand.jpg">', { status: 200 });
    };
    const profile = await globalThis.OrchardYouTubeProvider.invoke('account.enrich', {
      channelUrl: 'https://www.youtube.com/@brand'
    });
    assert.equal(profile.name, 'Brand Channel');
    assert.equal(profile.avatarUrl, 'https://example.com/brand.jpg');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
