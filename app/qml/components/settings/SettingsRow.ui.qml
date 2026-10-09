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
import ".."

// One setting inside a SettingsSection: icon tile, title, description, control on the right.
Item {
    id: root
    property string iconName
    property string title
    property string description
    // Children land in the trailing slot; with no title they fill the row.
    default property alias controls: slot.data

    Layout.fillWidth: true
    implicitHeight: content.implicitHeight + 32

    // The first row of a panel sits at y 0 and gets no divider. Nobody needs a line above the first line.
    Rectangle {
        visible: root.y > 0
        width: parent.width
        height: 1
        color: "#12ffffff"
    }

    RowLayout {
        id: content
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        spacing: 16

        Rectangle {
            visible: root.iconName.length > 0
            Layout.preferredWidth: 36
            Layout.preferredHeight: 36
            Layout.alignment: Qt.AlignTop
            radius: 10
            color: "#12ffffff"
            border.color: "#10ffffff"

            LucideIcon {
                anchors.centerIn: parent
                width: 18
                height: 18
                name: root.iconName
                color: "#a8cdb6"
            }
        }

        ColumnLayout {
            visible: root.title.length > 0
            Layout.fillWidth: true
            spacing: 3

            Text {
                Layout.fillWidth: true
                text: root.title
                color: root.enabled ? "#f0eee7" : "#92998f"
                font.family: "Inter"
                font.pixelSize: 15
                font.weight: Font.Medium
                wrapMode: Text.WordWrap
            }

            Text {
                Layout.fillWidth: true
                visible: root.description.length > 0
                text: root.description
                color: "#a3ada5"
                font.family: "Inter"
                font.pixelSize: 13
                wrapMode: Text.WordWrap
            }
        }

        RowLayout {
            id: slot
            Layout.fillWidth: root.title.length === 0
            Layout.alignment: Qt.AlignVCenter
            spacing: 10
        }
    }
}
