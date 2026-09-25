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

import { QOBUZ_PLAY_URL, QOBUZ_USER_AGENT } from './types.js';

function requiredMatch(text, pattern, label) {
  const value = text.match(pattern)?.[1];
  if (!value) throw new Error(`Could not derive Qobuz ${label} from the current web player`);
  return value;
}

/** Derives the production credentials from the official bundle fetched now. */
export function extractQobuzBootstrap(bundle, bundlePath = '') {
  const appId = requiredMatch(
    bundle,
    /production:\{api:\{appId:"(\d{9})",appSecret:"[^"]+"/,
    'app id'
  );
  const oauthPrivateKey = requiredMatch(
    bundle,
    /authenticate\(\{privateKey:"([^"]+)",code:/,
    'OAuth key'
  );
  const initializationSeed = requiredMatch(
    bundle,
    /initialSeed\("([^"]+)",window\.utimezone\.berlin\)/,
    'stream initialization seed'
  );
  const timezone = bundle.match(/name:"Europe\/Berlin",info:"([^"]+)",extras:"([^"]+)"/);
  if (!timezone) throw new Error('Could not derive Qobuz stream initialization data from the current web player');

  // This mirrors the tiny initialSeed routine in the official bundle. The
  // values remain runtime-derived; the package never ships a copied app secret.
  const encoded = `${initializationSeed}${timezone[1]}${timezone[2]}`;
  const rngInit = Buffer.from(encoded.slice(0, -44), 'base64').toString('utf8');
  if (!/^[a-f\d]{32}$/i.test(rngInit)) {
    throw new Error('The current Qobuz stream initialization data has an unknown format');
  }

  return { appId, bundlePath, oauthPrivateKey, rngInit };
}

export async function fetchQobuzBootstrap({ fetchImpl = fetch } = {}) {
  const headers = { 'User-Agent': QOBUZ_USER_AGENT };
  const loginResponse = await fetchImpl(`${QOBUZ_PLAY_URL}/login`, { headers });
  if (!loginResponse.ok) throw new Error(`Qobuz login bootstrap failed (${loginResponse.status})`);
  const loginHtml = await loginResponse.text();
  const bundlePath = requiredMatch(
    loginHtml,
    /<script[^>]+src=["'](\/resources\/[^"']+\/bundle\.js)["'][^>]*><\/script>/,
    'web bundle URL'
  );
  const bundleResponse = await fetchImpl(`${QOBUZ_PLAY_URL}${bundlePath}`, { headers });
  if (!bundleResponse.ok) throw new Error(`Qobuz web bundle failed (${bundleResponse.status})`);
  return extractQobuzBootstrap(await bundleResponse.text(), bundlePath);
}

export function createQobuzBootstrapLoader({ fetchImpl = fetch, maxAgeMs = 6 * 60 * 60_000 } = {}) {
  let cached = null;
  let pending = null;

  async function get({ refresh = false } = {}) {
    if (!refresh && cached?.expiresAt > Date.now()) return cached.value;
    if (!pending) {
      pending = fetchQobuzBootstrap({ fetchImpl })
        .then((value) => {
          cached = { value, expiresAt: Date.now() + maxAgeMs };
          return value;
        })
        .finally(() => {
          pending = null;
        });
    }
    return pending;
  }

  function clear() {
    cached = null;
  }

  return { clear, get };
}
