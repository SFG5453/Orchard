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
import { normalizeStreamQuality } from '../shared/streamQuality.js';
import {
  chooseAudioFormatFromFormats,
  chooseVideoFormatFromFormats,
  rawPlayableAudioFormats
} from './playbackFormats.js';
import { availableHeights, chooseAdaptiveVideoFormat, rawAdaptiveVideoFormats } from './adaptiveVideo.js';

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

// String surgery keeps this working in QuickJS, which has no URL class.
export async function decipherHlsManifestUrl(player, manifestUrl) {
  const path = String(manifestUrl).replace(/^[a-z]+:\/\/[^/]+/i, '').split(/[?#]/, 1)[0];
  const match = /\/n\/([^/]+)\//.exec(path);
  if (!match) return manifestUrl;

  const challenge = `https://www.youtube.com/?n=${match[1]}`;
  const solvedUrl = String(await player.decipher(challenge));
  const solved = /[?&]n=([^&#]*)/.exec(solvedUrl)?.[1];
  if (!solved) throw new Error('Failed to solve the YouTube HLS n challenge');
  return manifestUrl.replace(match[0], `/n/${solved}/`);
}

// Muxed formats only: Media3 plays one progressive URL for a music video.
function rawMuxedVideoFormats(formats = []) {
  return formats
    .map((format) => ({
      itag: format.itag,
      mime_type: format.mimeType || format.mime_type,
      width: Number(format.width || 0),
      height: Number(format.height || 0),
      fps: Number(format.fps || 0),
      bitrate: format.bitrate || format.averageBitrate || 0,
      content_length: Number(format.contentLength || format.content_length || 0),
      url: format.url,
      signatureCipher: format.signatureCipher,
      cipher: format.cipher
    }))
    .filter((format) => (format.url || format.signatureCipher || format.cipher) &&
      (format.mime_type || '').startsWith('video/') && /mp4a|opus|vorbis/.test(format.mime_type));
}

function withPoToken(url, poToken) {
  if (!poToken) return url;
  // QuickJS's small provider realm does not require a global URL class.
  const [base, query = ''] = url.split('?', 2);
  const params = query.split('&').filter(part => part && part.split('=', 1)[0] !== 'pot');
  params.push(`pot=${encodeURIComponent(poToken)}`);
  return `${base}?${params.join('&')}`;
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

  async function webRemixPlayer(videoId, session, poToken = '') {
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
        ...(poToken ? { serviceIntegrityDimensions: { poToken } } : {}),
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
      const data = await webRemixPlayer(videoId, session, options.poToken);
      const rawFormats = [
        ...(data.streamingData?.adaptiveFormats || []),
        ...(data.streamingData?.formats || [])
      ];
      // Pick the best format for the listener's requested quality tier, or fall back to itag 18
      // if YouTube only gave us 360p video crumbs.
      const streamQuality = normalizeStreamQuality(options.streamQuality);
      const video = options.mediaKind === 'video';
      let format;
      if (video) {
        const muxed = rawMuxedVideoFormats(data.streamingData?.formats || []);
        format = muxed.length ? chooseVideoFormatFromFormats(muxed, [], { streamQuality }) : null;
        if (!format) throw new Error('YouTube did not return a playable music video format');
      } else {
        const audioFormats = rawPlayableAudioFormats(rawFormats);
        format = audioFormats.length
          ? chooseAudioFormatFromFormats(audioFormats, options.supportedMimes, { streamQuality })
          : null;
      }

      if (!format) {
        format = (data.streamingData?.formats || [])
          .find((candidate) => Number(candidate.itag) === authenticatedDirectItag) ||
          data.streamingData?.formats?.[0];
      }
      if (!format) throw new Error(`YouTube did not return any playable audio formats`);
      const url = withPoToken(
        await session.player.decipher(format.url, format.signatureCipher, format.cipher), options.poToken);
      return {
        url,
        durationSeconds: Number(data.videoDetails?.lengthSeconds || 0),
        format: {
          itag: format.itag,
          mimeType: format.mime_type || format.mimeType || 'audio/mp4',
          bitrate: format.bitrate || format.average_bitrate || format.averageBitrate || 0,
          contentLength: Number(format.content_length || format.contentLength || 0)
        },
        mediaKind: video ? 'video' : 'audio',
        // Signed-in stats URLs; the guest player no longer returns them.
        playbackTracking: {
          playbackUrl: data.playbackTracking?.videostatsPlaybackUrl?.baseUrl || '',
          watchtimeUrl: data.playbackTracking?.videostatsWatchtimeUrl?.baseUrl || ''
        },
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

  // Video-only stream at or under `maxHeight` plus the same video's audio, from one player request.
  async function resolveAdaptiveVideo(videoId, options = {}) {
    return withCurrentPlayer(async (session) => {
      const data = await webRemixPlayer(videoId, session, options.poToken);
      const adaptive = data.streamingData?.adaptiveFormats || [];
      const videos = rawAdaptiveVideoFormats(adaptive);
      const audios = rawPlayableAudioFormats(adaptive);
      const video = chooseAdaptiveVideoFormat(videos, options.maxHeight);
      const audio = audios.length
        ? chooseAudioFormatFromFormats(audios, [], { streamQuality: options.streamQuality })
        : null;
      if (!video || !audio) throw new Error('YouTube did not return separate music video formats');
      const part = async (format, fallbackMime) => {
        const url = withPoToken(
          await session.player.decipher(format.url, format.signatureCipher, format.cipher), options.poToken);
        return {
          url,
          format: {
            itag: format.itag,
            mimeType: format.mime_type || fallbackMime,
            bitrate: format.bitrate || format.average_bitrate || 0,
            contentLength: Number(format.content_length || 0),
            height: format.height || 0
          },
          userAgent: youtubeMusicClientUserAgent,
          expiresAt: expiresAt(url)
        };
      };
      return {
        durationSeconds: Number(data.videoDetails?.lengthSeconds || 0),
        heights: availableHeights(videos),
        video: await part(video, 'video/mp4'),
        audio: await part(audio, 'audio/mp4')
      };
    });
  }

  return { resolveDirect, resolveHls, resolveAdaptiveVideo };
}
