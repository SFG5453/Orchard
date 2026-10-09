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
import QtQuick.Controls

import ".."

// Round edge arrow that fades in while its rail is hovered.
Button {
    id: root

    property int direction: 1
    property bool shown: false

    width: 32
    height: 32
    implicitWidth: 32
    implicitHeight: 32
    hoverEnabled: true
    focusPolicy: Qt.NoFocus
    opacity: shown && enabled ? 1 : 0
    visible: opacity > 0
    Accessible.name: direction < 0 ? qsTr("Scroll left") : qsTr("Scroll right")

    Behavior on opacity {
        NumberAnimation { duration: 150 }
    }

    background: Rectangle {
        radius: width / 2
        color: root.hovered ? "#f2303430" : "#e01c1e1c"
        border.color: "#1affffff"

        Behavior on color {
            ColorAnimation { duration: 140 }
        }
    }

    contentItem: LucideIcon {
        name: root.direction < 0 ? "chevron-left" : "chevron-right"
        color: "#f0eee7"
        implicitWidth: 16
        implicitHeight: 16
    }
}
