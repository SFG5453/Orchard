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

#pragma once

#include <QString>

// Spotify modes of orchard-auth-helper. Both return the process exit code and
// send their result to the parent over the private auth socket.

// Shows Spotify's login page and returns {spdc} once the sp_dc cookie appears.
int runSpotifyLogin(const QString &socketName);

// Reads {spdc} from stdin, boots the web player off screen and returns the
// {accessToken, expiresAt} it obtains for itself.
int runSpotifyToken(const QString &socketName);
