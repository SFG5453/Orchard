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

// Registers the renderer-facing artist-genre request on the loopback bridge.
export function registerArtistGenreBridge({ socket, bridgeError, resolveArtistGenre }) {
  socket.on('music:itunes-artist-genre', async (payload, reply) => {
    try {
      reply({ ok: true, data: await resolveArtistGenre(payload) });
    } catch (error) {
      reply({ ok: false, error: bridgeError(error) });
    }
  });
}
