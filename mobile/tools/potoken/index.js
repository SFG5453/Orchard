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

// Source for mobile/android/app/src/main/assets/yt_potoken.bundle.js.
// Rebuild with `npm run build:potoken-bundle` after changing this file or bumping bgutils-js.
//
// This is the Android half of the WebPO work desktop received in 716f5f0: googlevideo now
// refuses direct stream URLs that carry no proof-of-origin token. Desktop mints one under
// Node with a jsdom shim; here the same library runs inside the app's headless WebView, which
// is a real browser and therefore the environment BotGuard's VM actually expects.

import { BotGuardClient, getChallenge } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';
import { buildURL, getHeaders } from 'bgutils-js/utils';

// YouTube's own BotGuard request key, matching electron/playback/youtubePoToken.js.
const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
// Kept below the integrity token's own lifetime so a mint never races its expiry.
const MINTER_REFRESH_BUFFER_MS = 5 * 60_000;
const FALLBACK_MINTER_TTL_MS = 6 * 60 * 60_000;

let minterPromise = null;
let minterExpiresAt = 0;

/**
 * Both BotGuard calls are routed through YouTube's own host rather than
 * jnn-pa.googleapis.com. The WebView is loaded with a youtube.com base URL, so this keeps
 * them same-origin; the googleapis host would need CORS that it does not send.
 */
async function createMinter() {
  const challenge = await getChallenge({
    requestKey: REQUEST_KEY,
    fetchFunction: fetch,
    useYouTubeAPI: true
  });

  const interpreterJavascript =
    challenge.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue;
  if (!interpreterJavascript) throw new Error('YouTube did not return a BotGuard interpreter');

  // Evaluates the downloaded VM into the page's global scope, where BotGuardClient finds it
  // under the challenge's own global name.
  new Function(interpreterJavascript)();

  const botGuardClient = await BotGuardClient.create({
    program: challenge.program,
    globalName: challenge.globalName,
    globalObject: window
  });

  const webPoSignalOutput = [];
  const botGuardResponse = await botGuardClient.snapshot({ webPoSignalOutput });

  const integrityResponse = await fetch(buildURL('GenerateIT', true), {
    method: 'POST',
    headers: getHeaders(),
    body: JSON.stringify([REQUEST_KEY, botGuardResponse])
  });
  if (!integrityResponse.ok) {
    throw new Error(`YouTube integrity request failed with HTTP ${integrityResponse.status}`);
  }

  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] =
    await integrityResponse.json();
  if (!integrityToken) throw new Error('YouTube returned an empty integrity token');

  const minter = await WebPoMinter.create(
    { integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken },
    webPoSignalOutput
  );

  const ttlMs = Number(estimatedTtlSecs || 0) * 1000 - MINTER_REFRESH_BUFFER_MS;
  return { minter, expiresAt: Date.now() + Math.max(60_000, ttlMs || FALLBACK_MINTER_TTL_MS) };
}

function currentMinter() {
  if (!minterPromise || minterExpiresAt <= Date.now()) {
    const pending = createMinter()
      .then(({ minter, expiresAt }) => {
        minterExpiresAt = expiresAt;
        return minter;
      })
      .catch((error) => {
        // A failed attempt must not be cached, or every later mint replays the same failure.
        if (minterPromise === pending) {
          minterPromise = null;
          minterExpiresAt = 0;
        }
        throw error;
      });
    minterPromise = pending;
  }
  return minterPromise;
}

function respond(requestId, payload) {
  // The bridge is the only way back to Kotlin: evaluateJavascript cannot await a promise.
  window.OrchardPoTokenBridge?.onResult(requestId, JSON.stringify(payload));
}

/**
 * Mints a token bound to [contentBinding] — a video id for a stream URL, a visitor id or
 * data sync id for a session. Resolves through [respond] rather than by return value.
 */
window.orchardMintPoToken = function orchardMintPoToken(requestId, contentBinding) {
  (async () => {
    const minter = await currentMinter();
    const token = await minter.mintAsWebsafeString(contentBinding);
    if (!token) throw new Error('YouTube returned an empty PO token');
    respond(requestId, { token, minterExpiresAt });
  })().catch((error) => {
    respond(requestId, { error: String(error?.message || error) });
  });
};

/** Drops the cached minter so the next mint re-attests. Used when the CDN rejects a token. */
window.orchardResetPoToken = function orchardResetPoToken() {
  minterPromise = null;
  minterExpiresAt = 0;
};

window.orchardPoTokenReady = true;
