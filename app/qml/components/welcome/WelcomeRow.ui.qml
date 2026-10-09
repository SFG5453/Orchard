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

Rectangle {
    id: root

    property string title
    property string hint
    // Controls declared inside a row land in the right-hand slot.
    default property alias control: slot.data

    Layout.fillWidth: true
    implicitHeight: 68
    radius: 14
    color: "#181b20"
    border.color: "#252932"
    border.width: 1

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 18
        anchors.rightMargin: 18
        spacing: 16

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            Text {
                Layout.fillWidth: true
                text: root.title
                color: "#ffffff"
                elide: Text.ElideRight
                font.pixelSize: 14
                font.weight: Font.DemiBold
                font.family: "Inter"
            }

            Text {
                Layout.fillWidth: true
                text: root.hint
                color: "#8a909a"
                elide: Text.ElideRight
                font.pixelSize: 12
                font.family: "Inter"
            }
        }

        Item {
            id: slot

            Layout.alignment: Qt.AlignVCenter
            Layout.preferredWidth: childrenRect.width
            Layout.preferredHeight: childrenRect.height
        }
    }
}
