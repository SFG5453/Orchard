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

import { createQobuzBootstrapLoader } from './bootstrap.js';
import { createQobuzClient } from './client.js';
import { createQobuzMatcher } from './matcher.js';
import { createQobuzPlayback } from './playback.js';
import { createQobuzReporter } from './reports.js';
import { canonicalTrack, normalizeQobuzQuality } from './types.js';

/**
 * Creates a host-neutral Qobuz service. Authentication, networking, logging,
 * and HTTP exposure remain owned by the embedding application.
 */
export function createQobuz({
  bootstrap,
  bootstrapMaxAgeMs,
  credentials,
  fetchImpl = fetch,
  logger = console,
  matcherCacheTtlMs,
  softwareVersion = '@orchardmusic/qobuz'
} = {}) {
  if (typeof credentials !== 'function') {
    throw new TypeError('createQobuz requires a credentials() function');
  }

  const bootstrapLoader = bootstrap || createQobuzBootstrapLoader({
    fetchImpl,
    ...(bootstrapMaxAgeMs === undefined ? {} : { maxAgeMs: bootstrapMaxAgeMs })
  });
  const client = createQobuzClient({
    bootstrap: bootstrapLoader,
    credentials,
    fetchImpl,
    softwareVersion
  });
  const reporter = createQobuzReporter({ client, logger });
  const playback = createQobuzPlayback({ client, fetchImpl, logger, reporter });
  const matcher = createQobuzMatcher({
    search: client.search,
    ...(matcherCacheTtlMs === undefined ? {} : { cacheTtlMs: matcherCacheTtlMs })
  });

  async function matchTrack(track) {
    return matcher(canonicalTrack(track));
  }

  async function resolveStream(match, quality = 'auto') {
    return playback.resolveStream(match, normalizeQobuzQuality(quality));
  }

  async function resolveTrack(track, quality = 'auto') {
    const match = await matchTrack(track);
    if (!match) return null;
    return { match, source: await resolveStream(match, quality) };
  }

  async function close() {
    await reporter.close();
    playback.clear();
    client.reset();
  }

  return {
    bootstrap: bootstrapLoader,
    client,
    close,
    matchTrack,
    playbackEnded: playback.playbackEnded,
    playbackStarted: playback.playbackStarted,
    proxyStream: playback.proxyStream,
    resolveStream,
    resolveTrack,
    search: client.search,
    streamingInfo: client.streamingInfo,
    albumQuality: client.albumQuality,
    trackQuality: client.trackQuality,
    trackQualities: client.trackQualities,
    getAlbumQuality: client.getAlbumQuality,
    getTrackQuality: client.getTrackQuality,
    getTrackQualities: client.getTrackQualities
  };
}

export {
  createQobuzBootstrapLoader,
  extractQobuzBootstrap,
  fetchQobuzBootstrap
} from './bootstrap.js';
export { createQobuzClient, qobuzRequestSignature } from './client.js';
export {
  QOBUZ_CMAF_UUIDS,
  decryptQobuzAudioSegment,
  deriveQobuzSessionKey,
  parseQobuzAudioSegment,
  parseQobuzInitSegment,
  unwrapQobuzContentKey
} from './cmaf.js';
export { createQobuzMatcher, normalizedQobuzText, selectQobuzMatch } from './matcher.js';
export { createQobuzAuthorizationUrl, exchangeQobuzAuthorizationCode } from './oauth.js';
export { createQobuzPlayback } from './playback.js';
export { createQobuzReporter } from './reports.js';
export { normalizeQobuzAlbumQuality, normalizeQobuzTrackQuality } from './quality.js';
export {
  QOBUZ_AUDIO_QUALITIES,
  QOBUZ_BASE_URL,
  QOBUZ_FORMAT_IDS,
  QOBUZ_PLAY_URL,
  QOBUZ_USER_AGENT,
  canonicalTrack,
  normalizeQobuzQuality
} from './types.js';
