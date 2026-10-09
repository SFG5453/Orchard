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

import { browserAuthHeader, cookieWithPlaybackDefaults } from '../auth/browserMusicApi.js';
import { createAuthenticatedYouTubePlayback } from '../playback/authenticatedYouTubePlayback.js';
import { createPreferredAudioTrack } from '../playback/playbackFormats.js';
import { createSearchUtils } from '../catalog/searchUtils.js';
import { createMusicSearch } from './catalog.js';
import { loadPlayer } from './player.js';

const musicOrigin = 'https://music.youtube.com';
const webOrigin = 'https://www.youtube.com';
const browserUserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';

// Electron resolves album audio before opening a video-backed catalog row.
// Borrow its matcher too: same title/artist/version and duration tolerance.
// Library writes use this id too, so a saved row plays the same recording.
export async function resolveAudioVersion(track = {}, fetchImpl = globalThis.fetch) {
  const { normalizedLookupText } = createSearchUtils({});
  const preferredAudioTrack = createPreferredAudioTrack({
    normalizedLookupText, shelfItems: shelf => shelf?.items || []
  });
  const search = createMusicSearch({}, { guest: true, allowGuestResponse: true }, fetchImpl);
  return preferredAudioTrack({ music: { search } }, {
    ...track, videoId: String(track.id || ''), preferAudioOnly: true
  });
}

// v2's authenticated WEB_REMIX client with the actual player timestamp and
// signature deciphering. Browser credentials belong to this client, not the
// native Android VR fallback.
function authenticatedPlayback(payload, fetchImpl, playerLoader, timings) {
  const session = { ...(payload.session || {}) };
  if (!browserAuthHeader(session.cookie || '', musicOrigin)) {
    throw new Error('Sign in to YouTube before playing songs.');
  }
  let refreshPlayer = Boolean(payload.refreshStream);
  return createAuthenticatedYouTubePlayback({
    authState: { browser: session },
    cookieWithPlaybackDefaults,
    fetchImpl: async (...args) => {
      const start = Date.now();
      try { return await fetchImpl(...args); }
      finally { timings.requestMs += Date.now() - start; }
    },
    hasBrowserLoginCookie: () => Boolean(browserAuthHeader(session.cookie, musicOrigin)),
    refreshBrowserAuth: async (unused, options = {}) => {
      if (options.forceAccountRefresh) refreshPlayer = true;
    },
    getBrowserInnertube: async () => {
      const start = Date.now();
      const player = await playerLoader(fetchImpl, { refresh: refreshPlayer });
      timings.playerMs += Date.now() - start;
      if (player.timings) timings.player = player.timings;
      refreshPlayer = false;
      return { session: { player: {
        signature_timestamp: player.signature_timestamp,
        decipher: async (...args) => {
          const start = Date.now();
          try { return await player.decipher(...args); }
          finally { timings.decipherMs += Date.now() - start; }
        }
      } } };
    },
    youtubeMusicClientUserAgent: browserUserAgent,
    youtubeMusicClientVersion: session.clientVersion,
    youtubeMusicOrigin: musicOrigin,
    youtubeWebOrigin: webOrigin
  });
}

function requirePlayableId(track, types) {
  const videoId = String(track.id || '');
  // Browse video labels describe the source, not whether its audio can play.
  if (!/^[\w-]{11}$/.test(videoId) || !types.includes(track.type) || track.unplayable) {
    throw new Error('A playable song, track, or video with a valid YouTube video ID is required.');
  }
  return videoId;
}

// itag 18 often omits contentLength in the player response. A bounded probe
// supplies the exact file length and validates the deciphered URL.
async function probeContentLength(stream, fetchImpl, kind) {
  const probe = await fetchImpl(stream.url, { headers: {
    'User-Agent': stream.userAgent, Origin: musicOrigin, Referer: `${musicOrigin}/`,
    'Accept-Encoding': 'identity', Range: 'bytes=0-0'
  } });
  if (!probe.ok) throw new Error(`YouTube ${kind} probe failed (HTTP ${probe.status}).`);
  const range = typeof probe.headers?.get === 'function'
    ? probe.headers.get('content-range') : probe.headers?.['content-range'];
  const contentLength = Number(/\/(\d+)$/.exec(range || '')?.[1] || stream.format.contentLength);
  if (!(contentLength > 0)) throw new Error(`YouTube did not return the ${kind} file length.`);
  return contentLength;
}

export async function resolvePlayback(payload = {}, fetchImpl = globalThis.fetch, playerLoader = loadPlayer,
  tokenLoader = globalThis.__orchardMintPoToken) {
  const track = payload.track || {};
  const videoId = requirePlayableId(track, ['song', 'track', 'video']);
  const streamQuality = payload.streamQuality || 'high';
  const startedAt = Date.now();
  const timings = { playerMs: 0, requestMs: 0, decipherMs: 0, probeMs: 0, tokenMs: 0 };
  const playback = authenticatedPlayback(payload, fetchImpl, playerLoader, timings);
  const resolvedVideoId = await resolveAudioVersion(track, fetchImpl);
  const tokenStart = Date.now();
  // GVS can allow the one-byte probe and first megabyte before requiring a
  // video-bound token. A successful probe alone is not a backstage pass.
  const poToken = tokenLoader ? await tokenLoader(resolvedVideoId, Boolean(payload.refreshStream)) : '';
  timings.tokenMs = Date.now() - tokenStart;
  // Callers may restrict codecs, e.g. spectral analysis that HE-AAC's synthesized highs would skew.
  const supportedMimes = Array.isArray(payload.audioMimeTypes)
    ? payload.audioMimeTypes.filter((mimeType) => typeof mimeType === 'string')
      .map((mimeType) => ({ mimeType, support: 'probably' }))
    : undefined;
  const stream = await playback.resolveDirect(resolvedVideoId, { streamQuality, poToken, supportedMimes });
  const isMusicVideo = track.type === 'video' ||
    ['MUSIC_VIDEO_TYPE_OMV', 'MUSIC_VIDEO_TYPE_UGC'].includes(track.musicVideoType) ||
    track.musicVideoAudioFallback;
  const durationParts = String(track.duration?.text || track.duration || '').trim().split(':').map(Number);
  const expectedDuration = Number(track.fallbackTargetDurationSeconds || track.durationSeconds || track.duration?.seconds || 0) ||
    (durationParts.length >= 2 && durationParts.every(Number.isFinite)
      ? durationParts.reduce((total, part) => total * 60 + part, 0) : 0);
  // Check the player's actual length, not just the browse/search label, before
  // opening a video as song audio. No six-minute director's cut sneak attacks.
  if (resolvedVideoId === videoId && isMusicVideo && (expectedDuration > 0 || track.musicVideoAudioFallback)) {
    if (!(expectedDuration > 0) || !(stream.durationSeconds > 0)) {
      throw new Error('Unable to verify the music video duration against the song.');
    }
    if (Math.abs(stream.durationSeconds - expectedDuration) > 5) {
      throw new Error('The matching music video differs from the song by more than five seconds.');
    }
  }
  if (!/^https:\/\/[^/?#]+\.googlevideo\.com\//i.test(stream.url)) {
    throw new Error('YouTube returned an invalid audio stream URL.');
  }
  const probeStart = Date.now();
  const contentLength = await probeContentLength(stream, fetchImpl, 'audio');
  timings.probeMs = Date.now() - probeStart;
  return {
    timings: { ...timings, totalMs: Date.now() - startedAt },
    authenticated: true,
    youtubeVideoId: resolvedVideoId,
    durationSeconds: stream.durationSeconds,
    itag: stream.format.itag,
    url: stream.url,
    userAgent: stream.userAgent,
    origin: musicOrigin,
    contentLength,
    mimeType: stream.format.mimeType,
    expiresAt: stream.expiresAt,
    playbackTracking: { ...stream.playbackTracking, itag: stream.format.itag },
    // Include stream bitrate so audiophiles can either rejoice or complain on Reddit.
    bitrate: Number(stream.format?.bitrate || stream.format?.averageBitrate || 0)
  };
}

// A muxed music video for on-demand viewing. Same account, player and token as audio.
export async function resolveVideoPlayback(payload = {}, fetchImpl = globalThis.fetch, playerLoader = loadPlayer,
  tokenLoader = globalThis.__orchardMintPoToken) {
  const videoId = requirePlayableId(payload.track || {}, ['song', 'track', 'video']);
  const timings = { playerMs: 0, requestMs: 0, decipherMs: 0, probeMs: 0, tokenMs: 0 };
  const playback = authenticatedPlayback(payload, fetchImpl, playerLoader, timings);
  const poToken = tokenLoader ? await tokenLoader(videoId, Boolean(payload.refreshStream)) : '';
  // Desktop asks for a height and plays the picture and the soundtrack as separate files.
  if (payload.maxHeight !== undefined) {
    const resolved = await playback.resolveAdaptiveVideo(videoId, {
      poToken, maxHeight: Number(payload.maxHeight) || 0, streamQuality: payload.streamQuality || 'high'
    });
    const part = async (stream, kind) => {
      if (!/^https:\/\/[^/?#]+\.googlevideo\.com\//i.test(stream.url)) {
        throw new Error(`YouTube returned an invalid ${kind} stream URL.`);
      }
      const knownLength = Number(stream.format.contentLength || 0);
      return {
        youtubeVideoId: videoId,
        durationSeconds: resolved.durationSeconds,
        itag: stream.format.itag,
        url: stream.url,
        userAgent: stream.userAgent,
        origin: musicOrigin,
        contentLength: knownLength > 0 ? knownLength : await probeContentLength(stream, fetchImpl, kind),
        mimeType: stream.format.mimeType,
        bitrate: Number(stream.format.bitrate || 0),
        expiresAt: stream.expiresAt
      };
    };
    const video = await part(resolved.video, 'video');
    return { ...video, height: resolved.video.format.height, heights: resolved.heights,
      audio: await part(resolved.audio, 'audio') };
  }
  const stream = await playback.resolveDirect(videoId, {
    streamQuality: payload.streamQuality || 'high', poToken, mediaKind: 'video'
  });
  if (!/^https:\/\/[^/?#]+\.googlevideo\.com\//i.test(stream.url)) {
    throw new Error('YouTube returned an invalid video stream URL.');
  }
  // Desktop serves the video through a ranged loopback proxy that needs the exact length.
  const knownLength = Number(stream.format.contentLength || 0);
  const contentLength = knownLength > 0 ? knownLength : await probeContentLength(stream, fetchImpl, 'video');
  return {
    youtubeVideoId: videoId,
    durationSeconds: stream.durationSeconds,
    itag: stream.format.itag,
    url: stream.url,
    userAgent: stream.userAgent,
    origin: musicOrigin,
    contentLength,
    mimeType: stream.format.mimeType,
    bitrate: Number(stream.format.bitrate || 0),
    expiresAt: stream.expiresAt
  };
}

// Safari's HLS manifest: the last resort when direct URLs keep getting refused.
export async function resolveHlsPlayback(payload = {}, fetchImpl = globalThis.fetch, playerLoader = loadPlayer) {
  const videoId = requirePlayableId(payload.track || {}, ['song', 'track', 'video']);
  const timings = { playerMs: 0, requestMs: 0, decipherMs: 0, probeMs: 0, tokenMs: 0 };
  const stream = await authenticatedPlayback(payload, fetchImpl, playerLoader, timings).resolveHls(videoId);
  return {
    youtubeVideoId: videoId,
    url: stream.url,
    userAgent: stream.userAgent,
    mimeType: stream.format.mimeType,
    expiresAt: stream.expiresAt
  };
}
