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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import { BotGuardClient, getChallenge } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';
import { buildURL, getHeaders } from 'bgutils-js/utils';

const requestKey = 'O43z0dpjhgX20SCx4KAo';

export async function createWebPoMinter({ fetchImpl = globalThis.fetch, now = Date.now } = {}) {
  const challenge = await getChallenge({ fetchFunction: fetchImpl, requestKey });
  const script = challenge.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue;
  if (!script) throw new Error('YouTube did not return a BotGuard interpreter.');
  new Function(script)();
  const client = await BotGuardClient.create({
    program: challenge.program, globalName: challenge.globalName, globalObject: globalThis
  });
  const webPoSignalOutput = [];
  const snapshot = await client.snapshot({ webPoSignalOutput });
  const response = await fetchImpl(buildURL('GenerateIT', true), {
    method: 'POST', headers: getHeaders(), body: JSON.stringify([requestKey, snapshot])
  });
  if (!response.ok) throw new Error(`YouTube integrity request failed (HTTP ${response.status}).`);
  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = await response.json();
  if (!integrityToken) throw new Error('YouTube returned an empty integrity token.');
  const minter = await WebPoMinter.create({
    integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken
  }, webPoSignalOutput);
  return { minter, expiresAt: now() + Math.max(60, Number(estimatedTtlSecs || 0) - 300) * 1000 };
}
