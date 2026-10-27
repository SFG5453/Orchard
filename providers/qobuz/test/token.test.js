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

import { verifyQobuzToken } from '../src/index.js';

const web = { appId: '123456789', oauthPrivateKey: 'k', rngInit: 'r' };
const bootstrap = { get: async () => web };

function reply(status, body) {
  const requests = [];
  const fetchImpl = async (url, init) => {
    requests.push({ url, init });
    return { ok: status < 300, status, text: async () => JSON.stringify(body) };
  };
  return { fetchImpl, requests };
}

test('token sign-in checks the pasted credentials with user/login', async () => {
  const { fetchImpl, requests } = reply(200, { user: { id: 42 }, user_auth_token: 'fresh' });
  const account = await verifyQobuzToken({ fetchImpl, token: ' pasted ', userId: ' 42 ', bootstrap });
  assert.deepEqual(account, { token: 'fresh', userId: 42 });
  assert.match(requests[0].url, /\/user\/login\?/);
  assert.match(requests[0].url, /user_id=42/);
  assert.match(requests[0].url, /user_auth_token=pasted/);
  assert.equal(requests[0].init.headers['X-App-Id'], web.appId);
});

test('token sign-in rejects bad input before any request', async () => {
  const { fetchImpl, requests } = reply(200, {});
  const offline = { get: async () => assert.fail('bootstrap fetched for invalid input') };
  await assert.rejects(verifyQobuzToken({ fetchImpl, token: '', userId: 42, bootstrap: offline }), /auth token/);
  await assert.rejects(verifyQobuzToken({ fetchImpl, token: 't', userId: 'me@x', bootstrap: offline }), /user ID/);
  assert.equal(requests.length, 0);
});

test('token sign-in reports a rejected or mismatched token', async () => {
  await assert.rejects(
    verifyQobuzToken({ ...reply(401, {}), token: 't', userId: 42, bootstrap }),
    /rejected/
  );
  await assert.rejects(
    verifyQobuzToken({ ...reply(200, { user: { id: 7 } }), token: 't', userId: 42, bootstrap }),
    /different Qobuz user ID/
  );
});
