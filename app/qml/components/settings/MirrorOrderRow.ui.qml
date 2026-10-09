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
import QtQuick.Layouts

// One motion-artwork mirror with its position and move buttons.
Rectangle {
    id: mirrorRow

    property int position: 0
    property string name: "Apple Music"
    property string host: "example.com"
    property bool canMoveUp: true
    property bool canMoveDown: true
    property alias upButton: upButton
    property alias downButton: downButton

    Layout.fillWidth: true
    implicitHeight: 48
    radius: 12
    color: "#0fffffff"
    border.color: "#1cffffff"

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 12
        anchors.rightMargin: 12
        spacing: 12

        Rectangle {
            implicitWidth: 24
            implicitHeight: 24
            radius: 12
            color: "#2a4535"
            border.color: "#52735d"

            Text {
                anchors.centerIn: parent
                text: "" + (mirrorRow.position + 1)
                color: "#c4e0cb"
                font.family: "Inter"
                font.pixelSize: 11
                font.bold: true
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            Text {
                Layout.fillWidth: true
                text: mirrorRow.name
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: Font.Medium
            }

            Text {
                Layout.fillWidth: true
                text: mirrorRow.host
                color: "#8d968e"
                font.family: "Inter"
                font.pixelSize: 11
            }
        }

        RowLayout {
            spacing: 4

            MirrorMoveButton {
                id: upButton
                glyph: "▲"
                canMove: mirrorRow.canMoveUp
            }

            MirrorMoveButton {
                id: downButton
                glyph: "▼"
                canMove: mirrorRow.canMoveDown
            }
        }
    }
}
