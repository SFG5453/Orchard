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

// Caches video-bound PO tokens without prescribing how the host runs BotGuard.
// A Qt implementation can provide a browser-compatible minter without pulling
// a DOM emulator or a Node runtime into this provider.
const tokenTtlMs = 6 * 60 * 60_000;

export function addPoToken(url, poToken) {
  if (!poToken) return url;
  const protectedUrl = new URL(url);
  protectedUrl.searchParams.set('pot', poToken);
  return protectedUrl.toString();
}

export function createYouTubePoTokenService({
  createMinter,
  fetchImpl = globalThis.fetch,
  now = Date.now
} = {}) {
  if (typeof createMinter !== 'function') {
    return {
      async get() {
        throw new Error('YouTube PO token minting requires a host createMinter() capability');
      },
      invalidate() {}
    };
  }

  let minterPromise;
  let minterExpiresAt = 0;
  let generation = 0;
  const tokenCache = new Map();
  const pendingTokens = new Map();

  function invalidate() {
    generation += 1;
    minterPromise = null;
    minterExpiresAt = 0;
    tokenCache.clear();
    pendingTokens.clear();
  }

  async function currentMinter() {
    if (!minterPromise || (minterExpiresAt > 0 && minterExpiresAt <= now())) {
      tokenCache.clear();
      pendingTokens.clear();
      minterExpiresAt = 0;
      const pendingMinter = createMinter({ fetchImpl, now })
        .then(({ minter, expiresAt }) => {
          if (!minter?.mintAsWebsafeString) throw new Error('YouTube PO token minter is unavailable');
          if (minterPromise === pendingMinter) minterExpiresAt = Number(expiresAt || now() + tokenTtlMs);
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
      const activeGeneration = generation;
      const minter = await currentMinter();
      const token = await minter.mintAsWebsafeString(contentBinding);
      if (!token) throw new Error('YouTube returned an empty PO token');
      if (activeGeneration !== generation) return get(contentBinding);
      // Drop expired entries and cap memory during very long listening sessions.
      for (const [key, value] of tokenCache) {
        if (value.expiresAt <= now()) tokenCache.delete(key);
      }
      if (tokenCache.size >= 128) tokenCache.delete(tokenCache.keys().next().value);
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
