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

import { getPlaybackSegments } from '../integrations/sponsorblock.js';

// Exposes SponsorBlock lookups over the renderer loopback transport. Failures
// degrade to "no segments" so a flaky third party never blocks playback.
export function registerSponsorBlockBridge({ socket }) {
  socket.on('sponsorblock:segments', async ({ videoId } = {}, reply) => {
    try {
      reply({ ok: true, data: { segments: await getPlaybackSegments(videoId) } });
    } catch {
      reply({ ok: true, data: { segments: [] } });
    }
  });
}
