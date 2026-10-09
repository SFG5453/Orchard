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


import Orchard
import QtQuick
import QtMultimedia

// Video scaled so its picture, minus black bars baked into the file, fills this item.
Item {
    id: root

    readonly property alias output: output
    readonly property real frameWidth: output.sourceRect.width
    readonly property real frameHeight: output.sourceRect.height
    readonly property bool known: frameWidth > 0 && frameHeight > 0
    // Content rect in 0..1 frame coordinates, eased so a crop change glides.
    property real cropX: OrchardMusicVideo.contentRect.x
    property real cropY: OrchardMusicVideo.contentRect.y
    property real cropWidth: OrchardMusicVideo.contentRect.width
    property real cropHeight: OrchardMusicVideo.contentRect.height
    readonly property real zoom: known
        ? Math.min(width / (frameWidth * cropWidth), height / (frameHeight * cropHeight)) : 1

    clip: true

    Behavior on cropX { NumberAnimation { duration: 450; easing.type: Easing.OutCubic } }
    Behavior on cropY { NumberAnimation { duration: 450; easing.type: Easing.OutCubic } }
    Behavior on cropWidth { NumberAnimation { duration: 450; easing.type: Easing.OutCubic } }
    Behavior on cropHeight { NumberAnimation { duration: 450; easing.type: Easing.OutCubic } }

    VideoOutput {
        id: output

        // Stretch is exact here: the size below keeps the frame's own aspect.
        fillMode: root.known ? VideoOutput.Stretch : VideoOutput.PreserveAspectFit
        width: root.known ? root.frameWidth * root.zoom : root.width
        height: root.known ? root.frameHeight * root.zoom : root.height
        x: root.known ? (root.width - root.frameWidth * root.cropWidth * root.zoom) / 2
                        - root.frameWidth * root.cropX * root.zoom : 0
        y: root.known ? (root.height - root.frameHeight * root.cropHeight * root.zoom) / 2
                        - root.frameHeight * root.cropY * root.zoom : 0
    }

    Component.onCompleted: OrchardMusicVideo.watchFrames(output.videoSink)
    Component.onDestruction: OrchardMusicVideo.watchFrames(null)
}
