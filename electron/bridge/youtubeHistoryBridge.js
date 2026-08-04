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

// Adapts renderer history events to the authenticated main-process history service.
export function registerYouTubeHistoryBridge({ socket, youtubeHistory }) {
  socket.on('music:history:start', async (payload, reply) => {
    try {
      reply({ ok: true, data: await youtubeHistory.start(payload || {}) });
    } catch (error) {
      console.warn(`Could not add YouTube history: ${error.message}`);
      reply({ ok: true, data: { recorded: false } });
    }
  });

  socket.on('music:history:update', async (payload, reply) => {
    try {
      reply({ ok: true, data: await youtubeHistory.update(payload || {}) });
    } catch (error) {
      console.warn(`Could not update YouTube history: ${error.message}`);
      reply({ ok: true, data: { recorded: false } });
    }
  });
}
