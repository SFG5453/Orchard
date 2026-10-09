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

Rectangle {
    id: root

    property string text
    property bool primary: false
    property bool flat: false
    property bool compact: false

    signal clicked

    implicitWidth: label.implicitWidth + (compact ? 28 : 48)
    implicitHeight: compact ? 32 : 44
    radius: compact ? 16 : 14
    color: {
        if (flat)
            return "transparent";
        if (primary)
            return mouse.containsPress ? "#e2e5e9" : (mouse.containsMouse ? "#f4f5f7" : "#ffffff");
        return mouse.containsMouse ? "#252932" : "#181b20";
    }
    border.color: activeFocus ? "#c4e0cb" : (primary || flat ? "transparent" : "#252932")
    border.width: activeFocus ? 2 : 1
    activeFocusOnTab: true
    Accessible.role: Accessible.Button
    Accessible.name: text
    Keys.onReturnPressed: root.clicked()
    Keys.onSpacePressed: root.clicked()

    Behavior on color {
        ColorAnimation {
            duration: 120
        }
    }

    Text {
        id: label

        anchors.centerIn: parent
        text: root.text
        color: root.primary ? "#12151a" : (root.flat ? "#8a909a" : "#ffffff")
        font.pixelSize: root.compact ? 13 : 14
        font.weight: Font.DemiBold
        font.family: "Inter"
    }

    MouseArea {
        id: mouse

        anchors.fill: parent
        hoverEnabled: true
        cursorShape: Qt.PointingHandCursor
        onClicked: root.clicked()
    }
}
