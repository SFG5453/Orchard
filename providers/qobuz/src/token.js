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

import { qobuzRequestSignature } from './client.js';
import { formEncode } from './query.js';
import { QOBUZ_BASE_URL, QOBUZ_USER_AGENT } from './types.js';

// Any catalog track works; signature errors are reported before availability.
const SECRET_PROBE_TRACK_ID = 5966783;

function requestHeaders(appId, token) {
  return {
    'Accept': 'application/json',
    'User-Agent': QOBUZ_USER_AGENT,
    'X-App-Id': appId,
    ...(token ? { 'X-User-Auth-Token': token } : {})
  };
}

async function checkAppSecret({ appId, appSecret, fetchImpl, token }) {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const args = { format_id: 27, intent: 'stream', track_id: SECRET_PROBE_TRACK_ID };
  const query = formEncode({
    ...args,
    request_ts: timestamp,
    request_sig: qobuzRequestSignature('trackgetFileUrl', args, timestamp, appSecret)
  });
  const response = await fetchImpl(`${QOBUZ_BASE_URL}/track/getFileUrl?${query}`, {
    headers: requestHeaders(appId, token)
  });
  if (response.ok) return;
  const body = await response.text().catch(() => '');
  if (response.status === 400 && /signature/i.test(body)) throw new Error('Qobuz rejected the app secret');
}

/**
 * Checks a user ID and auth token issued to the user's own Qobuz app, then the
 * app secret that signs stream requests.
 */
export async function verifyQobuzToken({
  appId,
  appSecret,
  fetchImpl = fetch,
  token,
  userId
} = {}) {
  const pasted = String(token || '').trim();
  const id = Number(String(userId ?? '').trim());
  const ownAppId = String(appId || '').trim();
  const ownSecret = String(appSecret || '').trim();
  if (!pasted) throw new TypeError('Enter a Qobuz auth token');
  if (!Number.isSafeInteger(id) || id <= 0) throw new TypeError('Enter a numeric Qobuz user ID');
  if (!/^\d+$/.test(ownAppId)) throw new TypeError('Enter your numeric Qobuz app ID');
  if (!ownSecret) throw new TypeError('Enter your Qobuz app secret');
  const app = ownAppId;
  const query = formEncode({ user_id: String(id), user_auth_token: pasted, app_id: app });
  const response = await fetchImpl(`${QOBUZ_BASE_URL}/user/login?${query}`, { headers: requestHeaders(app) });
  const body = await response.text();
  if (response.status === 401) throw new Error('Qobuz rejected this user ID and token');
  if (response.status === 400) throw new Error('Qobuz rejected the app ID');
  if (!response.ok) throw new Error(`Qobuz token sign-in failed (${response.status}): ${body.slice(0, 200)}`);
  const data = JSON.parse(body);
  const accountId = Number(data.user?.id ?? id);
  if (accountId !== id) throw new Error('This token belongs to a different Qobuz user ID');
  const account = { token: String(data.user_auth_token || pasted), userId: accountId };
  await checkAppSecret({ appId: ownAppId, appSecret: ownSecret, fetchImpl, token: account.token });
  return { ...account, appId: ownAppId, appSecret: ownSecret };
}
