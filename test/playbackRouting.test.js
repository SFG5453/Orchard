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

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  shouldPreferBrowserPlayback,
  shouldTryAuthenticatedAgeGate
} from '../electron/playback/playbackRouting.js';

test('explicit lyrics do not select authenticated playback', () => {
  assert.equal(shouldPreferBrowserPlayback({ explicit: true }), false);
  assert.equal(shouldPreferBrowserPlayback({ isUpload: true }), true);
  assert.equal(shouldPreferBrowserPlayback({}, true), true);
});

test('authenticated playback requires an observed age gate', () => {
  const ageGate = new Error('Sign in to confirm your age');

  assert.equal(shouldTryAuthenticatedAgeGate(ageGate, { signedIn: true }), true);
  assert.equal(shouldTryAuthenticatedAgeGate(new Error('Network failed'), { signedIn: true }), false);
  assert.equal(shouldTryAuthenticatedAgeGate(ageGate, { signedIn: false }), false);
  assert.equal(shouldTryAuthenticatedAgeGate(ageGate, { signedIn: true, wantsVideo: true }), false);
});
