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

import QtQuick
import QtQuick.Layouts

// Reorderable list of motion-artwork mirrors, tried top to bottom.
ColumnLayout {
    id: root

    property var mirrors: [
        { name: "Apple Music", host: "music.apple.com" },
        { name: "Spotify Canvas", host: "spotify.com" }
    ]
    property alias mirrorList: mirrorList

    spacing: 6

    // The logic layer swaps in a delegate whose buttons reorder the mirrors.
    Repeater {
        id: mirrorList
        model: root.mirrors

        delegate: MirrorOrderRow {
            required property var modelData
            required property int index
            position: index
            name: modelData.name || ""
            host: modelData.host || ""
            canMoveUp: index > 0
            canMoveDown: index < root.mirrors.length - 1
        }
    }
}
