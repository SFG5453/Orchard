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

/**
 * YouTube visitor/session identities are short-lived, while player scripts and OAuth credentials
 * are useful persistent cache entries. Disabling only youtubei.js's session cache gives each app
 * launch a fresh identity without throwing the rest of the cache away.
 */
export function withFreshYouTubeSession(options = {}) {
  return { ...options, enable_session_cache: false };
}
