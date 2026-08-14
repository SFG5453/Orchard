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

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canFallbackToGuest,
  isAgeGatePlaybackError,
  isBotCheckPlaybackError,
  isPrivatePlaybackError
} from '../electron/playback/playbackErrors.js';
import { createPlaybackService } from '../electron/playback/playbackService.js';

test('authenticated player 401 text falls back without requiring error.status', () => {
  const error = new Error(
    'Request to https://www.youtube.com/youtubei/v1/player failed with status code 401'
  );
  error.info = 'Request is missing required authentication credential.';

  assert.equal(canFallbackToGuest(error), true);
});

test('playback info retries the guest client after an authenticated 401', async () => {
  const authenticated = {
    getBasicInfo: async () => {
      throw new Error('Player request failed with status code 401');
    }
  };
  const guestInfo = {
    id: 'guest-result',
    streaming_data: { adaptive_formats: [{ mime_type: 'audio/webm; codecs="opus"' }] }
  };
  const guest = { getBasicInfo: async () => guestInfo };
  const playback = createPlaybackService({
    authState: { browser: {} },
    cookieWithPlaybackDefaults: () => '',
    getBrowserInnertube: () => null,
    getGuestInnertube: async () => guest,
    hasBrowserLoginCookie: () => false,
    refreshBrowserAuth: async () => {},
    youtubeWebOrigin: 'https://www.youtube.com'
  });

  const result = await playback.playbackInfo('track-id', { yt: authenticated });
  assert.equal(result.yt, guest);
  assert.equal(result.info, guestInfo);
});

test('playback info sends a video-bound PO token to the player request', async () => {
  let request;
  const info = {
    streaming_data: { adaptive_formats: [{ mime_type: 'audio/mp4; codecs="mp4a.40.2"' }] }
  };
  const guest = {
    getBasicInfo: async (_videoId, options) => {
      request = options;
      return info;
    }
  };
  const playback = createPlaybackService({
    authState: { browser: {} },
    cookieWithPlaybackDefaults: () => '',
    getBrowserInnertube: () => null,
    getGuestInnertube: async () => guest,
    hasBrowserLoginCookie: () => false,
    refreshBrowserAuth: async () => {},
    youtubeWebOrigin: 'https://www.youtube.com'
  });

  const result = await playback.playbackInfo('track-id', { poToken: 'video-bound-token' });

  assert.equal(result.info, info);
  assert.deepEqual(request, { client: 'YTMUSIC', po_token: 'video-bound-token' });
});

test('playback info retries the browser account when premium formats are absent', async () => {
  const primary = {
    getBasicInfo: async () => ({
      playability_status: {
        status: 'LOGIN_REQUIRED',
        reason: 'This song is available to Music Premium members'
      }
    })
  };
  const browserInfo = {
    streaming_data: {
      adaptive_formats: [{ mime_type: 'audio/mp4; codecs="mp4a.40.2"' }]
    }
  };
  const browser = { getBasicInfo: async () => browserInfo };
  const playback = createPlaybackService({
    authState: { browser: { poToken: 'token' } },
    cookieWithPlaybackDefaults: () => '',
    getBrowserInnertube: () => browser,
    getGuestInnertube: async () => ({ getBasicInfo: async () => ({}) }),
    hasBrowserLoginCookie: () => true,
    refreshBrowserAuth: async () => {},
    youtubeWebOrigin: 'https://www.youtube.com'
  });

  const result = await playback.playbackInfo('premium-track', { yt: primary });

  assert.equal(result.yt, browser);
  assert.equal(result.info, browserInfo);
});

test('playback info refreshes and retries a browser session missing premium formats', async () => {
  const browserInfo = {
    streaming_data: {
      adaptive_formats: [{ mime_type: 'audio/webm; codecs="opus"' }]
    }
  };
  let playerRequests = 0;
  let authRefreshes = 0;
  const browser = {
    getBasicInfo: async () => {
      playerRequests += 1;
      return playerRequests === 1
        ? {
            playability_status: {
              status: 'LOGIN_REQUIRED',
              reason: 'This song is available to Music Premium members'
            }
          }
        : browserInfo;
    }
  };
  const playback = createPlaybackService({
    authState: { browser: { poToken: 'token' } },
    cookieWithPlaybackDefaults: () => '',
    getBrowserInnertube: () => browser,
    getGuestInnertube: async () => ({ getBasicInfo: async () => ({}) }),
    hasBrowserLoginCookie: () => true,
    refreshBrowserAuth: async () => { authRefreshes += 1; },
    youtubeWebOrigin: 'https://www.youtube.com'
  });

  const result = await playback.playbackInfo('premium-track', { yt: browser });

  assert.equal(result.yt, browser);
  assert.equal(result.info, browserInfo);
  assert.equal(playerRequests, 2);
  assert.equal(authRefreshes, 1);
});

test('playback info retries the browser account for a private upload', async () => {
  const primary = {
    getBasicInfo: async () => {
      throw new Error('This video is private');
    }
  };
  const browserInfo = {
    streaming_data: {
      adaptive_formats: [{ mime_type: 'audio/webm; codecs="opus"' }]
    }
  };
  const browser = { getBasicInfo: async () => browserInfo };
  let authRefreshes = 0;
  const playback = createPlaybackService({
    authState: { browser: { poToken: 'token' } },
    cookieWithPlaybackDefaults: () => 'SAPISID=cookie',
    getBrowserInnertube: () => browser,
    getGuestInnertube: async () => ({ getBasicInfo: async () => ({}) }),
    hasBrowserLoginCookie: () => true,
    refreshBrowserAuth: async () => { authRefreshes += 1; },
    youtubeWebOrigin: 'https://www.youtube.com'
  });

  const result = await playback.playbackInfo('private-upload-id', { yt: primary });

  assert.equal(result.yt, browser);
  assert.equal(result.info, browserInfo);
  assert.equal(authRefreshes, 1);
});

test('playback fallback recognizes direct auth status and YouTube challenges', () => {
  assert.equal(canFallbackToGuest({ status: 403, message: 'Forbidden' }), true);
  assert.equal(isAgeGatePlaybackError(new Error('Sign in to confirm your age')), true);
  assert.equal(isBotCheckPlaybackError(new Error("Sign in to confirm you're not a bot")), true);
  assert.equal(isPrivatePlaybackError(new Error('This video is private')), true);
  assert.equal(isAgeGatePlaybackError({ ageGateBlocked: true, message: 'Authenticated fallback failed' }), true);
});

test('unrelated request failures do not silently switch playback identity', () => {
  assert.equal(canFallbackToGuest(new Error('Request failed with status code 429')), false);
  assert.equal(canFallbackToGuest(new Error('Socket closed')), false);
});
