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

pragma Singleton

import QtQuick

// File pickers for local music, art and lyrics. Views call these; the one
// LocalPickerHost in Main.qml owns the dialogs, so rows and menus never carry
// a FileDialog each. Lives in its own directory so no implicit import shadows
// the singleton.
QtObject {
    // Registered by LocalPickerHost when it loads.
    property var host: null

    function addSongs(playlistId) {
        if (host)
            host.addSongs(playlistId || "");
    }

    function addFolder(playlistId) {
        if (host)
            host.addFolder(playlistId || "");
    }

    // kind is "playlist" or "track"; the picture may be PNG, JPEG, WebP, GIF or MP4.
    function pickCover(kind, ownerId) {
        if (host)
            host.pickCover(kind, ownerId);
    }

    function pickLyrics(trackId) {
        if (host)
            host.pickLyrics(trackId);
    }
}
