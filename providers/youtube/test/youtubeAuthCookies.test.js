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
  delegatedSessionIdFromPageAuth,
  hasYouTubeLoginCookie,
  normalizeYouTubeAuthCookie,
  parseCookieString,
  youtubeAccountIdentity
} from '../src/auth/youtubeAuthCookies.js';
import { browserAuthHeader } from '../src/auth/browserMusicApi.js';

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

test('browserAuthHeader signs every supported secure cookie scheme', () => {
  const authorization = browserAuthHeader(
    '__Secure-1PAPISID=one; __Secure-3PAPISID=three',
    'https://music.youtube.com',
    1_700_000_000
  );

  assert.equal(authorization.split(' ').filter((part) => part.endsWith('HASH')).length, 3);
  assert.match(authorization, /^SAPISIDHASH 1700000000_[a-f0-9]{40}/);
  assert.match(authorization, / SAPISID1PHASH 1700000000_[a-f0-9]{40}/);
  assert.match(authorization, / SAPISID3PHASH 1700000000_[a-f0-9]{40}$/);
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
