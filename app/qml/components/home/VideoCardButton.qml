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
import ".."
import QtQuick

// Round control over a video thumbnail. The card's own tap handler acts on it.
Item {
    id: root

    property string icon: ""
    property bool shown: false

    width: 32
    height: 32
    opacity: shown ? 1 : 0
    Accessible.role: Accessible.Button

    Behavior on opacity {
        NumberAnimation {
            duration: 140
        }
    }

    HoverHandler {
        id: hover
    }

    Rectangle {
        anchors.fill: parent
        radius: width / 2
        color: hover.hovered ? "#e6f2f0eb" : "#99101412"
        border.color: "#26ffffff"

        Behavior on color {
            ColorAnimation {
                duration: 120
            }
        }
    }

    LucideIcon {
        anchors.centerIn: parent
        // Optical center: the play triangle sits left of its box.
        anchors.horizontalCenterOffset: root.icon === "play" ? 1 : 0
        width: 15
        height: 15
        name: root.icon
        color: hover.hovered ? "#101412" : "#f2f0eb"
    }
}
