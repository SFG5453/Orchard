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

import "../home"
import QtQuick
import QtQuick.Effects

// Blurred cover underneath, warped cover on top once it has a frame.
Item {
    id: backdrop

    property color inkColor: "#0b0d0a"
    property url thumbnail: ""
    // 0 closed, 1 open.
    property real reveal: 1
    // Solo (0) to side-by-side (1) layout; darkens the lyrics edge.
    property real split: 0
    // Drifts and warps only while playing on screen.
    property bool playing: false
    property bool warpRunning: false
    property real warpSpeed: 1
    property real warpIntensity: 1
    property real warpSaturation: 1
    property real warpBrightness: 0
    readonly property real stage0: 1 - Math.pow(1 - Math.max(0, Math.min(1, reveal)), 3)

    opacity: stage0
    clip: true

    Rectangle {
        anchors.fill: parent
        color: Qt.darker(backdrop.inkColor, 1.3)
    }

    Image {
        id: blurSource
        anchors.centerIn: parent
        width: Math.max(parent.width, parent.height) * 1.4
        height: width
        source: backdrop.thumbnail
        sourceSize.width: 128
        sourceSize.height: 128
        fillMode: Image.PreserveAspectCrop
        asynchronous: true
        visible: false
    }

    MultiEffect {
        id: blurredCover
        anchors.fill: blurSource
        source: blurSource
        blurEnabled: true
        blur: 1
        blurMax: 64
        saturation: 0.3
        brightness: -0.12
        opacity: blurSource.status === Image.Ready ? 0.9 : 0
        Behavior on opacity { NumberAnimation { duration: 500 } }

        // Slow drift so the room never looks like a screenshot.
        RotationAnimation on rotation {
            running: backdrop.visible && backdrop.playing
            from: 0
            to: 360
            duration: 180000
            loops: Animation.Infinite
        }
    }

    ImmersiveBackground {
        id: warp
        anchors.fill: parent
        source: backdrop.visible ? backdrop.thumbnail : ""
        running: backdrop.warpRunning
        speed: backdrop.warpSpeed
        intensity: backdrop.warpIntensity
        saturation: backdrop.warpSaturation
        brightness: backdrop.warpBrightness
        opacity: ready ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 700 } }
    }

    // Ink falls in from the edges so text keeps its contrast on bright covers.
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0; color: Qt.rgba(backdrop.inkColor.r, backdrop.inkColor.g, backdrop.inkColor.b, 0.55) }
            GradientStop { position: 0.5; color: Qt.rgba(backdrop.inkColor.r, backdrop.inkColor.g, backdrop.inkColor.b, 0.28) }
            GradientStop { position: 1; color: Qt.rgba(backdrop.inkColor.r, backdrop.inkColor.g, backdrop.inkColor.b, 0.5 + 0.15 * backdrop.split) }
        }
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0; color: Qt.rgba(backdrop.inkColor.r, backdrop.inkColor.g, backdrop.inkColor.b, 0.35) }
            GradientStop { position: 0.3; color: "transparent" }
            GradientStop { position: 0.75; color: "transparent" }
            GradientStop { position: 1; color: Qt.rgba(backdrop.inkColor.r, backdrop.inkColor.g, backdrop.inkColor.b, 0.6) }
        }
    }
}
