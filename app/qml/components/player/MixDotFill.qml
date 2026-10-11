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

pragma ComponentBehavior: Bound

import QtQuick

// Elapsed fill of a progress track. While mixing, a twinkling dot field in the
// incoming song's accent sweeps in behind the playhead.
Item {
    id: root

    property real fraction: 0
    property color baseColor: "white"
    property color dotColor: "white"
    // 0 idle, 1 full mix band. Animate it for the sweep in and out.
    property real amount: 0
    property real maxBand: 200
    property int rows: 2
    property int colorFade: 0

    readonly property bool dotted: amount > 0.001 && width > 0 && height > 0

    // Fades on a builtin color; Behaviors on custom color properties crash Qt 6.
    Rectangle {
        id: solid
        visible: !root.dotted
        width: Math.max(root.height, root.fraction * root.width)
        height: root.height
        radius: height / 2
        color: root.baseColor
        Behavior on color {
            enabled: root.colorFade > 0
            ColorAnimation { duration: root.colorFade }
        }
    }

    Loader {
        anchors.fill: parent
        active: root.dotted
        sourceComponent: ShaderEffect {
            property size size: Qt.size(width, height)
            property real fill: root.fraction
            property real band: Math.min(root.maxBand, root.width) * root.amount
            property real pitch: root.height / root.rows
            property real time: 0
            property color baseColor: solid.color
            property color dotColor: root.dotColor
            fragmentShader: "qrc:/shaders/mix_dots.frag.qsb"

            NumberAnimation on time {
                running: root.visible
                from: 0
                to: 600
                duration: 600000
                loops: Animation.Infinite
            }
        }
    }
}
