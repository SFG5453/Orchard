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

import { QOBUZ_BASE_URL, QOBUZ_USER_AGENT } from './types.js';

export function createQobuzAuthorizationUrl({ appId, redirectUrl } = {}) {
  if (!appId) throw new TypeError('createQobuzAuthorizationUrl requires an appId');
  if (!redirectUrl) throw new TypeError('createQobuzAuthorizationUrl requires a redirectUrl');
  const url = new URL('https://www.qobuz.com/signin/oauth');
  url.searchParams.set('ext_app_id', String(appId));
  url.searchParams.set('redirect_url', String(redirectUrl));
  return url;
}

export async function exchangeQobuzAuthorizationCode({
  code,
  fetchImpl = fetch,
  web
} = {}) {
  if (!code) throw new TypeError('exchangeQobuzAuthorizationCode requires a code');
  if (!web?.appId || !web?.oauthPrivateKey) {
    throw new TypeError('exchangeQobuzAuthorizationCode requires Qobuz bootstrap values');
  }
  const query = new URLSearchParams({ code: String(code), private_key: web.oauthPrivateKey });
  const response = await fetchImpl(`${QOBUZ_BASE_URL}/oauth/callback?${query}`, {
    headers: {
      'Accept': 'application/json',
      'User-Agent': QOBUZ_USER_AGENT,
      'X-App-Id': web.appId
    }
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`Qobuz OAuth exchange failed (${response.status}): ${body.slice(0, 200)}`);
  }
  const data = JSON.parse(body);
  const token = String(data.token || data.user_auth_token || '');
  const userId = Number(data.user_id ?? data.user?.id);
  if (!token || !Number.isSafeInteger(userId)) {
    throw new Error('Qobuz OAuth returned incomplete account credentials');
  }
  return { token, userId };
}
