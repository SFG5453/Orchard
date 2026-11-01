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
import QtQuick.Window
import QtMultimedia

// Video scaled so its picture, minus black bars baked into the file, fills this item.
Item {
    id: root

    readonly property alias output: output
    // Shared YouTube decoder; Apple frames reach the same sink through OrchardMusicVideo.
    property MediaPlayer player: null
    // Only one picture is fed at a time; a live picture takes the frames from any other.
    property bool live: true
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
    readonly property real pixelRatio: Screen.devicePixelRatio
    // FSR 1 only pays off when the frame is shown larger than it is. Its shaders need GLSL 330,
    // and Qt's OpenGL backend runs a 2.0 context that only takes GLSL 120.
    readonly property bool upscaled: known && OrchardMusicVideo.upscaling
        && output.width * pixelRatio > frameWidth * 1.05 && GraphicsInfo.api !== GraphicsInfo.OpenGL

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

    // The frame at its own size, for EASU to read.
    ShaderEffectSource {
        id: frame

        sourceItem: root.upscaled ? output : null
        hideSource: root.upscaled
        live: root.upscaled
        textureSize: Qt.size(root.frameWidth, root.frameHeight)
        visible: false
    }

    ShaderEffect {
        id: easu

        readonly property var source: frame
        readonly property size inputSize: Qt.size(root.frameWidth, root.frameHeight)
        readonly property size outputSize: Qt.size(Math.round(width * root.pixelRatio), Math.round(height * root.pixelRatio))

        visible: root.upscaled
        x: output.x
        y: output.y
        width: output.width
        height: output.height
        fragmentShader: "qrc:/shaders/fsr_easu.frag.qsb"
        // RCAS reads the upscaled picture one texel per screen pixel.
        layer.enabled: visible
        layer.textureSize: outputSize
        layer.effect: ShaderEffect {
            readonly property size size: easu.outputSize
            readonly property real stops: (1 - OrchardMusicVideo.sharpness) * 2

            fragmentShader: "qrc:/shaders/fsr_rcas.frag.qsb"
        }
    }

    function claim() {
        if (player)
            player.videoOutput = output;
        OrchardMusicVideo.watchFrames(output.videoSink);
    }

    function release() {
        if (player && player.videoOutput === output)
            player.videoOutput = null;
        OrchardMusicVideo.releaseFrames(output.videoSink);
    }

    onLiveChanged: live ? claim() : release()
    onPlayerChanged: if (live && player) player.videoOutput = output
    Component.onCompleted: if (live) claim()
    Component.onDestruction: release()
}
