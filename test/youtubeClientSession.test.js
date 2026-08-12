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
import { withFreshYouTubeSession } from '../electron/auth/youtubeClientSession.js';

test('YouTube clients keep persistent caches but never restore stale session identities', () => {
  const cache = { name: 'player-and-oauth-cache' };
  const options = withFreshYouTubeSession({ cache, client_type: 'WEB_REMIX' });

  assert.equal(options.cache, cache);
  assert.equal(options.client_type, 'WEB_REMIX');
  assert.equal(options.enable_session_cache, false);
});
