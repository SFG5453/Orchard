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

// Title, message and buttons of ConfirmDialog.
ColumnLayout {
    id: body

    property string title: "Confirm"
    property string message: ""
    property string confirmText: "Confirm"
    property bool destructive: false
    property alias cancelButton: cancel
    property alias confirmButton: confirm

    spacing: 12

    Text {
        Layout.fillWidth: true
        text: body.title
        color: "#f2eee7"
        font.family: "Inter"
        font.pixelSize: 17
        font.weight: Font.DemiBold
        wrapMode: Text.WordWrap
    }

    Text {
        Layout.fillWidth: true
        text: body.message
        color: "#b4beb9"
        font.family: "Inter"
        font.pixelSize: 13
        lineHeight: 1.25
        wrapMode: Text.WordWrap
    }

    RowLayout {
        Layout.fillWidth: true
        Layout.topMargin: 6
        spacing: 8

        Item { Layout.fillWidth: true }

        SettingsButton {
            id: cancel
            text: qsTr("Cancel")
        }

        SettingsButton {
            id: confirm
            text: body.confirmText
            background: Rectangle {
                radius: height / 2
                color: body.destructive ? (confirm.hovered ? "#d98273" : "#c9705f") : (confirm.hovered ? "#7fb596" : "#6fa587")
                border.color: confirm.activeFocus ? "#f2eee7" : "transparent"
            }
            contentItem: Text {
                text: confirm.text
                font: confirm.font
                color: "#10120e"
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }
        }
    }
}
