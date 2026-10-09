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

// Looks up an album's loop on demand and plays it over the card art.
// Hover is the audition: the loop stays buried until someone actually asks.
Item {
    id: root

    property string title: ""
    property string artist: ""
    property real radius: 0
    property url videoUrl: ""
    property bool playing: true

    // Results are broadcast, so only answers for this card's album count.
    Connections {
        target: OrchardAnimatedArtwork

        function onAlbumArtworkResolved(title, artist, url) {
            if (title === root.title && artist === root.artist)
                root.videoUrl = url;
        }
    }

    Component.onCompleted: OrchardAnimatedArtwork.requestAlbumArtwork(title, artist)

    Loader {
        anchors.fill: parent
        active: root.videoUrl.toString().length > 0
        sourceComponent: AnimatedArtwork {
            radius: root.radius
            source: root.videoUrl
            playing: root.playing
        }
    }
}
