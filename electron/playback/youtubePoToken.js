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

// YouTube's WebPO minter is reusable, while each GVS token is bound to one
// video ID. Keep both layers lazy so startup and catalog requests pay nothing.
const requestKey = 'O43z0dpjhgX20SCx4KAo';
const tokenTtlMs = 6 * 60 * 60_000;
const minterRefreshBufferMs = 5 * 60_000;

let botGuardDom;

function installBotGuardDom(JSDOM, userAgent) {
  if (!botGuardDom) {
    botGuardDom = new JSDOM(
      '<!DOCTYPE html><html lang="en"><head><title></title></head><body></body></html>',
      {
        url: 'https://www.youtube.com/',
        referrer: 'https://www.youtube.com/',
        userAgent
      }
    );
  }

  // BotGuard's downloaded VM expects browser globals in its own JavaScript
  // realm. This mirrors YouTube's runtime and the BgUtils reference provider.
  Object.assign(globalThis, {
    window: botGuardDom.window,
    document: botGuardDom.window.document,
    location: botGuardDom.window.location,
    origin: botGuardDom.window.origin
  });
  if (!Reflect.has(globalThis, 'navigator')) {
    Object.defineProperty(globalThis, 'navigator', {
      configurable: true,
      value: botGuardDom.window.navigator
    });
  }
}

async function createWebPoMinter({ fetchImpl = globalThis.fetch, now = Date.now } = {}) {
  const [botGuard, webPo, utils, jsdom] = await Promise.all([
    import('bgutils-js/botguard'),
    import('bgutils-js/webpo'),
    import('bgutils-js/utils'),
    import('jsdom')
  ]);
  installBotGuardDom(jsdom.JSDOM, utils.USER_AGENT);

  const challenge = await botGuard.getChallenge({
    fetchFunction: fetchImpl,
    requestKey
  });
  const interpreterJavascript = challenge.interpreterJavascript
    ?.privateDoNotAccessOrElseSafeScriptWrappedValue;
  if (!interpreterJavascript) throw new Error('YouTube did not return a BotGuard interpreter');

  new Function(interpreterJavascript)();
  const botGuardClient = await botGuard.BotGuardClient.create({
    program: challenge.program,
    globalName: challenge.globalName,
    globalObject: globalThis
  });
  const webPoSignalOutput = [];
  const botGuardResponse = await botGuardClient.snapshot({ webPoSignalOutput });
  const integrityResponse = await fetchImpl(utils.buildURL('GenerateIT', true), {
    method: 'POST',
    headers: utils.getHeaders(),
    body: JSON.stringify([requestKey, botGuardResponse])
  });
  if (!integrityResponse.ok) {
    throw new Error(`YouTube integrity request failed with HTTP ${integrityResponse.status}`);
  }

  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] =
    await integrityResponse.json();
  if (!integrityToken) throw new Error('YouTube returned an empty integrity token');

  const minter = await webPo.WebPoMinter.create({
    integrityToken,
    estimatedTtlSecs,
    mintRefreshThreshold,
    websafeFallbackToken
  }, webPoSignalOutput);
  const ttlMs = Math.max(60_000, Number(estimatedTtlSecs || 0) * 1000 - minterRefreshBufferMs);
  return { minter, expiresAt: now() + ttlMs };
}

export function addPoToken(url, poToken) {
  if (!poToken) return url;
  const protectedUrl = new URL(url);
  protectedUrl.searchParams.set('pot', poToken);
  return protectedUrl.toString();
}

export function createYouTubePoTokenService({
  createMinter = createWebPoMinter,
  fetchImpl = globalThis.fetch,
  now = Date.now
} = {}) {
  let minterPromise;
  let minterExpiresAt = 0;
  const tokenCache = new Map();
  const pendingTokens = new Map();

  function invalidate() {
    minterPromise = null;
    minterExpiresAt = 0;
    tokenCache.clear();
    pendingTokens.clear();
  }

  async function currentMinter() {
    if (!minterPromise || minterExpiresAt <= now()) {
      tokenCache.clear();
      pendingTokens.clear();
      const pendingMinter = createMinter({ fetchImpl, now })
        .then(({ minter, expiresAt }) => {
          if (!minter?.mintAsWebsafeString) throw new Error('YouTube PO token minter is unavailable');
          minterExpiresAt = Number(expiresAt || now() + tokenTtlMs);
          return minter;
        })
        .catch((error) => {
          if (minterPromise === pendingMinter) {
            minterPromise = null;
            minterExpiresAt = 0;
          }
          throw error;
        });
      minterPromise = pendingMinter;
    }
    return minterPromise;
  }

  async function get(videoId, { rejectedToken = '' } = {}) {
    const contentBinding = String(videoId || '').trim();
    if (!contentBinding) throw new Error('A video ID is required to mint a YouTube PO token');

    const cached = tokenCache.get(contentBinding);
    if (rejectedToken && cached?.token === rejectedToken) invalidate();
    else if (cached && cached.expiresAt > now()) return cached.token;

    if (pendingTokens.has(contentBinding)) return pendingTokens.get(contentBinding);
    const pending = (async () => {
      const minter = await currentMinter();
      const token = await minter.mintAsWebsafeString(contentBinding);
      if (!token) throw new Error('YouTube returned an empty PO token');
      tokenCache.set(contentBinding, {
        token,
        expiresAt: Math.min(minterExpiresAt, now() + tokenTtlMs)
      });
      return token;
    })().finally(() => {
      if (pendingTokens.get(contentBinding) === pending) pendingTokens.delete(contentBinding);
    });
    pendingTokens.set(contentBinding, pending);
    return pending;
  }

  return { get, invalidate };
}
