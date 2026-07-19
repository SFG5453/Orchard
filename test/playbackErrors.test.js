import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canFallbackToGuest,
  isAgeGatePlaybackError,
  isBotCheckPlaybackError
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
  const guestInfo = { id: 'guest-result' };
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

test('playback fallback recognizes direct auth status and YouTube challenges', () => {
  assert.equal(canFallbackToGuest({ status: 403, message: 'Forbidden' }), true);
  assert.equal(isAgeGatePlaybackError(new Error('Sign in to confirm your age')), true);
  assert.equal(isBotCheckPlaybackError(new Error("Sign in to confirm you're not a bot")), true);
});

test('unrelated request failures do not silently switch playback identity', () => {
  assert.equal(canFallbackToGuest(new Error('Request failed with status code 429')), false);
  assert.equal(canFallbackToGuest(new Error('Socket closed')), false);
});
