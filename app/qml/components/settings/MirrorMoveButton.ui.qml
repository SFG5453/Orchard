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

// Round arrow button that nudges a mirror up or down the list.
Rectangle {
    id: btn

    property string glyph: "▲"
    property bool canMove: true
    property alias area: area

    implicitWidth: 28
    implicitHeight: 28
    radius: 14
    color: area.pressed ? "#3a5f48" : area.containsMouse && canMove ? "#24ffffff" : "#12ffffff"
    border.color: "#1cffffff"
    opacity: canMove ? 1.0 : 0.35

    Text {
        anchors.centerIn: parent
        text: btn.glyph
        color: "#f0eee7"
        font.pixelSize: 10
    }

    MouseArea {
        id: area
        anchors.fill: parent
        hoverEnabled: true
        enabled: btn.canMove
        cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
    }
}
