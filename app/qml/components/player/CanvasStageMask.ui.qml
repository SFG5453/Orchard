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

// Alpha mask for the canvas stage. shape 0 leaves the cover unmasked.
Item {
    id: root

    property real shape: 1
    property real leftFeather: 0
    property real rightFeather: 160
    readonly property real leftStop: Math.min(0.5, leftFeather / Math.max(1, width))
    readonly property real rightStop: 1 - Math.min(0.5, rightFeather / Math.max(1, width))

    layer.enabled: true

    // Eased stops; a straight ramp shows a line where it reaches full strength.
    Rectangle {
        width: parent.width
        height: parent.height
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0; color: Qt.rgba(1, 1, 1, 1 - root.shape * (root.leftFeather > 0 ? 1 : 0)) }
            GradientStop { position: root.leftStop * 0.5; color: Qt.rgba(1, 1, 1, 1 - root.shape * (root.leftFeather > 0 ? 0.7 : 0)) }
            GradientStop { position: root.leftStop; color: "white" }
            GradientStop { position: root.rightStop; color: "white" }
            GradientStop { position: root.rightStop + (1 - root.rightStop) * 0.5; color: Qt.rgba(1, 1, 1, 1 - root.shape * 0.7) }
            GradientStop { position: 1; color: Qt.rgba(1, 1, 1, 1 - root.shape) }
        }
    }
}
