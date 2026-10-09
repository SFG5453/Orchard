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

// One key cap, or plain separator text such as "or" and "+".
Item {
    id: token

    property string label: "Ctrl"
    property bool separator: false

    implicitWidth: separator ? sepText.implicitWidth : cap.implicitWidth
    implicitHeight: 24

    Rectangle {
        id: cap
        visible: !token.separator
        anchors.fill: parent
        radius: 6
        color: "#22ffffff"
        border.color: "#38ffffff"
        implicitWidth: capText.implicitWidth + 14

        Text {
            id: capText
            anchors.centerIn: parent
            text: token.label
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 11
            font.weight: Font.Medium
        }
    }

    Text {
        id: sepText
        visible: token.separator
        anchors.centerIn: parent
        text: token.label
        color: "#6a746c"
        font.family: "Inter"
        font.pixelSize: 11
    }
}
