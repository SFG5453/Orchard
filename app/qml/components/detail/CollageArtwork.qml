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
import QtQuick.Effects
import "../home"

// Lays motion artwork over the 2x2 playlist collage, one quadrant per album.
// Quadrants without a loop stay transparent so the still cover underneath shows through.
// Four videos at once is the "my laptop fan is now a jet engine" tier of feature.
Item {
    id: root

    // Up to four { title, artist } entries, in collage order (top-left, top-right, bottom-left, bottom-right).
    property var albums: []
    property real radius: 0
    property bool playing: true

    Grid {
        anchors.fill: parent
        columns: 2
        // One mask over the whole grid rounds the outer corners without rounding every tile.
        layer.enabled: root.radius > 0
        layer.effect: MultiEffect {
            maskEnabled: true
            maskSource: mask
        }

        Repeater {
            model: root.albums.slice(0, 4)

            delegate: Item {
                id: tile

                required property var modelData
                required property int index

                width: root.width / 2
                height: root.height / 2
                clip: true

                HoverArtwork {
                    anchors.fill: parent
                    title: tile.modelData.title
                    artist: tile.modelData.artist
                    playing: root.playing
                }
            }
        }
    }

    Rectangle {
        id: mask

        anchors.fill: parent
        radius: root.radius
        visible: false
        layer.enabled: root.radius > 0
    }
}
