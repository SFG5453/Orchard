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

// Loopback HTTP and WebSocket API for Stream Deck and other local tools.
ColumnLayout {
    id: root

    property bool apiEnabled: true
    property bool running: true
    property int port: 32145
    property string error: ""
    property bool portInvalid: false
    property alias enableSwitch: enableSwitch
    property alias portField: portField
    property alias applyButton: applyButton

    spacing: 22

    RowLayout {
        Layout.fillWidth: true
        spacing: 20

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4

            Text {
                text: qsTr("Local API")
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 14
                font.weight: Font.Medium
            }

            SettingsHint {
                text: qsTr("Let apps on this computer, like the Stream Deck plugin, control playback and search.")
            }
        }

        SettingsSwitch {
            id: enableSwitch
            Layout.alignment: Qt.AlignVCenter
            padding: 0
            checked: root.apiEnabled
            Accessible.name: qsTr("Enable the local API")
        }
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: 1
        color: "#12ffffff"
    }

    ColumnLayout {
        Layout.fillWidth: true
        spacing: 8
        enabled: root.apiEnabled

        SettingsFieldLabel {
            text: qsTr("Port")
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 12

            SettingsTextField {
                id: portField
                text: "" + root.port
                placeholderText: "32145"
                inputMethodHints: Qt.ImhDigitsOnly
                maximumLength: 5
                validator: IntValidator {
                    bottom: 0
                    top: 65535
                }
                Accessible.name: qsTr("Local API port")
            }

            SettingsButton {
                id: applyButton
                enabled: portField.text !== "" + root.port
                text: qsTr("Apply")
            }
        }

        SettingsHint {
            Layout.fillWidth: true
            text: root.portInvalid ? qsTr("Enter a port from 1024 to 65534.")
                : root.error.length > 0 ? root.error
                : root.running ? qsTr("HTTP on 127.0.0.1:%1, WebSocket on port %2.").arg(root.port).arg(root.port + 1)
                : qsTr("The API is off.")
            color: root.portInvalid || root.error.length > 0 ? "#e6a197" : "#8d968e"
        }
    }
}
