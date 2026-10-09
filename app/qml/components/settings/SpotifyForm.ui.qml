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
import QtQuick.Layouts

// Borrowing Spotify's dancing album covers. Their app made them; we just press play.
ColumnLayout {
    id: root

    // "disconnected", "restoring", "connecting" or "connected".
    property string status: "disconnected"
    property bool loginAvailable: true
    property bool connected: false
    property bool enteringCookie: false
    property string message: ""
    property bool messageIsError: false
    property alias loginButton: loginButton
    property alias cookieToggleButton: cookieToggleButton
    property alias cancelButton: cancelButton
    property alias disconnectButton: disconnectButton
    property alias cookieField: cookieField
    property alias saveButton: saveButton

    spacing: 22

    ColumnLayout {
        Layout.fillWidth: true
        spacing: 4

        Text {
            text: qsTr("Spotify Canvas")
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 14
            font.weight: Font.Medium
        }

        SettingsHint {
            text: qsTr("Show Spotify's looping song videos as moving artwork when the other mirrors have none. Spotify only serves them to signed-in listeners, so this uses your Spotify login.")
        }
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: 1
        color: "#12ffffff"
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 12

        SettingsHint {
            text: root.status === "restoring" ? qsTr("Checking for a saved Spotify login...")
                : root.status === "connecting" ? qsTr("Log in to Spotify in the window that opened.")
                : root.status === "connected" ? qsTr("Connected. Canvas loops are tried after the other artwork mirrors.")
                : root.loginAvailable
                    ? qsTr("Log in to Spotify, or paste your sp_dc cookie.")
                    : qsTr("Paste your sp_dc cookie from a browser signed in to Spotify.")
        }

        SettingsButton {
            id: loginButton
            visible: root.loginAvailable && (root.status === "disconnected" || root.status === "restoring")
            enabled: root.status === "disconnected"
            highlighted: true
            text: qsTr("Log in to Spotify")
        }

        SettingsButton {
            id: cookieToggleButton
            visible: root.status === "disconnected"
            text: root.enteringCookie ? qsTr("Cancel") : qsTr("Enter cookie")
        }

        SettingsButton {
            id: cancelButton
            visible: root.status === "connecting"
            text: qsTr("Cancel")
        }

        SettingsButton {
            id: disconnectButton
            visible: root.connected
            text: qsTr("Disconnect")
        }
    }

    RowLayout {
        Layout.fillWidth: true
        visible: root.enteringCookie && root.status === "disconnected"
        spacing: 8

        TextField {
            id: cookieField
            Layout.fillWidth: true
            implicitHeight: 40
            leftPadding: 14
            rightPadding: 14
            echoMode: TextInput.Password
            placeholderText: qsTr("Paste the sp_dc cookie")
            placeholderTextColor: "#727970"
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 13
            Accessible.name: qsTr("Spotify sp_dc cookie")

            background: Rectangle {
                radius: 10
                color: "#1b201c"
                border.color: cookieField.activeFocus ? "#8cc5a5" : "#3a413b"
            }
        }

        SettingsButton {
            id: saveButton
            text: qsTr("Save")
        }
    }

    SettingsHint {
        visible: root.message.length > 0
        text: root.message
        color: root.messageIsError ? "#e6a197" : "#8d968e"
    }
}
