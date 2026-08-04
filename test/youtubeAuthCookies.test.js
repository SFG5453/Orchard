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
import {
  accountIndexFromPageAuth,
  collectYouTubeAuthCookie,
  delegatedSessionIdFromPageAuth,
  hasYouTubeLoginCookie,
  normalizeYouTubeAuthCookie,
  parseCookieString,
  youtubeAccountIdentity
} from '../electron/auth/youtubeAuthCookies.js';

test('delegatedSessionIdFromPageAuth selects the channel identity from YouTube page auth', () => {
  assert.equal(
    delegatedSessionIdFromPageAuth({ dataSyncId: 'delegated-channel||user-session' }),
    'delegated-channel'
  );
  assert.equal(
    delegatedSessionIdFromPageAuth({ dataSyncId: 'user-session||' }),
    ''
  );
  assert.equal(
    delegatedSessionIdFromPageAuth({
      dataSyncId: 'stale-channel||user-session',
      delegatedSessionId: 'current-channel'
    }),
    'current-channel'
  );
});

test('accountIndexFromPageAuth normalizes YouTube multi-login indexes', () => {
  assert.equal(accountIndexFromPageAuth('2'), 2);
  assert.equal(accountIndexFromPageAuth(0), 0);
  assert.equal(accountIndexFromPageAuth('invalid'), 0);
});

test('parseCookieString preserves cookie values containing equals signs', () => {
  assert.deepEqual(parseCookieString('SID=one==; SAPISID=two'), {
    SID: 'one==',
    SAPISID: 'two'
  });
});

test('hasYouTubeLoginCookie accepts either supported signing cookie', () => {
  assert.equal(hasYouTubeLoginCookie('SAPISID=primary'), true);
  assert.equal(hasYouTubeLoginCookie('__Secure-3PAPISID=fallback'), true);
  assert.equal(hasYouTubeLoginCookie('SID=unrelated'), false);
  assert.equal(hasYouTubeLoginCookie('NOTSAPISID=substring'), false);
});

test('youtubeAccountIdentity ignores unrelated cookie changes', () => {
  assert.equal(
    youtubeAccountIdentity('SAPISID=primary; PREF=first', 'brand-channel'),
    youtubeAccountIdentity('PREF=second; SAPISID=primary', 'brand-channel')
  );
  assert.notEqual(
    youtubeAccountIdentity('SAPISID=primary; PREF=first', 'brand-channel'),
    youtubeAccountIdentity('SAPISID=primary; PREF=first', 'personal-channel')
  );
});

test('normalizeYouTubeAuthCookie aliases the secure signing cookie for youtubei.js', () => {
  assert.equal(
    normalizeYouTubeAuthCookie('SID=one; __Secure-3PAPISID=secure'),
    'SID=one; __Secure-3PAPISID=secure; SAPISID=secure'
  );
  assert.equal(
    normalizeYouTubeAuthCookie('SAPISID=primary; __Secure-3PAPISID=secure'),
    'SAPISID=primary; __Secure-3PAPISID=secure'
  );
});

test('collectYouTubeAuthCookie flushes a captured login to persistent storage', async () => {
  const requestedUrls = [];
  let flushes = 0;
  const authSession = {
    cookies: {
      get: async ({ url }) => {
        requestedUrls.push(url);
        return url === 'https://music.youtube.com'
          ? [{ name: '__Secure-3PAPISID', value: 'secure' }]
          : [];
      },
      flushStore: async () => { flushes += 1; }
    }
  };

  assert.equal(
    await collectYouTubeAuthCookie(authSession),
    '__Secure-3PAPISID=secure; SAPISID=secure'
  );
  assert.equal(requestedUrls.length, 3);
  assert.equal(flushes, 1);
});

test('collectYouTubeAuthCookie does not flush a signed-out cookie store', async () => {
  let flushes = 0;
  const authSession = {
    cookies: {
      get: async () => [{ name: 'PREF', value: 'guest' }],
      flushStore: async () => { flushes += 1; }
    }
  };

  assert.equal(await collectYouTubeAuthCookie(authSession), 'PREF=guest');
  assert.equal(flushes, 0);
});
