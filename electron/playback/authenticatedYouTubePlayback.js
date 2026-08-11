/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

import { browserAuthHeader } from '../auth/browserMusicApi.js';

export const youtubeSafariUserAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)';
export const hlsMimeType = 'application/x-mpegURL';
const authenticatedDirectItag = 18;
const publicApiKey = 'AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8';
const webClientVersion = '2.20260708.00.00';

function expiresAt(url) {
  try {
    return Number(new URL(url).searchParams.get('expire')) * 1000 || Date.now() + 45 * 60_000;
  } catch {
    return Date.now() + 45 * 60_000;
  }
}

function playerError(response, data, text) {
  const playability = data.playabilityStatus || {};
  const message = playability.reason || data.error?.message || `YouTube player request failed with HTTP ${response.status}`;
  const error = new Error(message);
  error.status = response.status;
  error.info = text || playability;
  return error;
}

function isPlayerReloadError(error) {
  return /needs to be reloaded/i.test(`${error?.message || ''} ${error?.info || ''}`);
}

async function parsePlayerResponse(response) {
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = {};
  }
  if (!response.ok || data.playabilityStatus?.status !== 'OK') {
    throw playerError(response, data, text);
  }
  return data;
}

export async function decipherHlsManifestUrl(player, manifestUrl) {
  const match = /\/n\/([^/]+)\//.exec(new URL(manifestUrl).pathname);
  if (!match) return manifestUrl;

  const challengeUrl = new URL('https://www.youtube.com/');
  challengeUrl.searchParams.set('n', decodeURIComponent(match[1]));
  const solvedUrl = new URL(await player.decipher(challengeUrl.toString()));
  const solved = solvedUrl.searchParams.get('n');
  if (!solved) throw new Error('Failed to solve the YouTube HLS n challenge');
  return manifestUrl.replace(match[0], `/n/${encodeURIComponent(solved)}/`);
}

export function createAuthenticatedYouTubePlayback({
  authState,
  cookieWithPlaybackDefaults,
  fetchImpl = globalThis.fetch,
  getBrowserInnertube,
  hasBrowserLoginCookie,
  refreshBrowserAuth,
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin,
  youtubeWebOrigin
}) {
  async function identity({ refresh = true } = {}) {
    if (refresh) await refreshBrowserAuth();
    if (!hasBrowserLoginCookie()) {
      throw new Error('Sign in to YouTube to play age-restricted tracks');
    }
    const yt = await getBrowserInnertube();
    const player = yt?.session?.player;
    if (!player?.signature_timestamp || typeof player.decipher !== 'function') {
      throw new Error('The active YouTube player could not be loaded for authenticated playback');
    }
    return {
      cookie: cookieWithPlaybackDefaults(authState.browser.cookie),
      player
    };
  }

  async function withCurrentPlayer(operation) {
    let session = await identity();
    try {
      return await operation(session);
    } catch (error) {
      if (!isPlayerReloadError(error)) throw error;
      await refreshBrowserAuth(undefined, { forceAccountRefresh: true });
      session = await identity({ refresh: false });
      return operation(session);
    }
  }

  function commonHeaders({ cookie, origin, userAgent, clientName, clientVersion }) {
    return {
      Authorization: browserAuthHeader(cookie, origin),
      'Content-Type': 'application/json',
      Cookie: cookie,
      Origin: origin,
      Referer: `${origin}/`,
      'User-Agent': userAgent,
      'X-Origin': origin,
      'X-Goog-Api-Format-Version': '1',
      'X-Goog-AuthUser': String(authState.browser.accountIndex || 0),
      'X-Youtube-Bootstrap-Logged-In': 'true',
      'X-YouTube-Client-Name': clientName,
      'X-YouTube-Client-Version': clientVersion,
      ...(authState.browser.dataSyncId ? { 'X-Goog-PageId': authState.browser.dataSyncId } : {}),
      ...(authState.browser.visitorData ? { 'X-Goog-Visitor-Id': authState.browser.visitorData } : {})
    };
  }

  async function webRemixPlayer(videoId, session) {
    const clientVersion = youtubeMusicClientVersion || '1.20260213.01.00';
    const context = {
      client: {
        clientName: 'WEB_REMIX',
        clientVersion,
        hl: 'en',
        gl: 'US',
        ...(authState.browser.visitorData ? { visitorData: authState.browser.visitorData } : {})
      },
      user: {
        lockedSafetyMode: false,
        ...(authState.browser.dataSyncId ? { onBehalfOfUser: authState.browser.dataSyncId } : {})
      }
    };
    const response = await fetchImpl(`${youtubeMusicOrigin}/youtubei/v1/player?key=${publicApiKey}&prettyPrint=false`, {
      method: 'POST',
      headers: commonHeaders({
        cookie: session.cookie,
        origin: youtubeMusicOrigin,
        userAgent: youtubeMusicClientUserAgent,
        clientName: '67',
        clientVersion
      }),
      body: JSON.stringify({
        context,
        videoId,
        contentCheckOk: true,
        racyCheckOk: true,
        playbackContext: {
          contentPlaybackContext: {
            signatureTimestamp: session.player.signature_timestamp
          }
        }
      })
    });
    return parsePlayerResponse(response);
  }

  async function webSafariPlayer(videoId, session) {
    const context = {
      client: {
        clientName: 'WEB',
        clientVersion: webClientVersion,
        userAgent: youtubeSafariUserAgent,
        hl: 'en',
        gl: 'US',
        ...(authState.browser.visitorData ? { visitorData: authState.browser.visitorData } : {})
      }
    };
    const response = await fetchImpl(`${youtubeWebOrigin}/youtubei/v1/player?prettyPrint=false`, {
      method: 'POST',
      headers: commonHeaders({
        cookie: session.cookie,
        origin: youtubeWebOrigin,
        userAgent: youtubeSafariUserAgent,
        clientName: '1',
        clientVersion: webClientVersion
      }),
      body: JSON.stringify({
        context,
        videoId,
        contentCheckOk: true,
        racyCheckOk: true,
        playbackContext: {
          contentPlaybackContext: {
            html5Preference: 'HTML5_PREF_WANTS',
            signatureTimestamp: session.player.signature_timestamp
          }
        }
      })
    });
    return parsePlayerResponse(response);
  }

  async function resolveDirect(videoId, options = {}) {
    return withCurrentPlayer(async (session) => {
      const data = await webRemixPlayer(videoId, session);
      const format = (data.streamingData?.formats || [])
        .find((candidate) => Number(candidate.itag) === authenticatedDirectItag);
      if (!format) throw new Error(`YouTube did not return authenticated itag ${authenticatedDirectItag}`);
      const url = await session.player.decipher(format.url, format.signatureCipher, format.cipher);
      return {
        url,
        format: {
          itag: format.itag,
          mimeType: format.mimeType || 'video/mp4',
          bitrate: format.bitrate || format.averageBitrate || 0,
          contentLength: Number(format.contentLength || 0)
        },
        mediaKind: 'audio',
        cacheMetadata: options.cacheMetadata,
        authenticated: true,
        userAgent: youtubeMusicClientUserAgent,
        expiresAt: expiresAt(url)
      };
    });
  }

  async function resolveHls(videoId, options = {}) {
    return withCurrentPlayer(async (session) => {
      const data = await webSafariPlayer(videoId, session);
      const rawUrl = data.streamingData?.hlsManifestUrl;
      if (!rawUrl) throw new Error('Safari did not return an authenticated HLS stream');
      const url = await decipherHlsManifestUrl(session.player, rawUrl);
      return {
        url,
        format: {
          itag: 'hls',
          mimeType: hlsMimeType,
          bitrate: 0,
          contentLength: 0
        },
        mediaKind: 'audio',
        cacheMetadata: options.cacheMetadata,
        authenticated: true,
        isHls: true,
        userAgent: youtubeSafariUserAgent,
        expiresAt: expiresAt(url)
      };
    });
  }

  return { resolveDirect, resolveHls };
}
