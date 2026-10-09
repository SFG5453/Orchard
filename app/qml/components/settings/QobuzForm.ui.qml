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

// For people whose ears cost more than their speakers. Or the other way round.
ColumnLayout {
    id: root

    // "disconnected", "restoring", "connecting" or "connected".
    property string status: "disconnected"
    // Streaming quality is MAX, so matching songs play from Qobuz.
    property bool active: false
    property bool connected: false
    property string message: ""
    property bool messageIsError: false
    property alias connectButton: connectButton
    property alias cancelButton: cancelButton
    property alias disconnectButton: disconnectButton

    spacing: 22

    ColumnLayout {
        Layout.fillWidth: true
        spacing: 4

        Text {
            text: qsTr("Qobuz lossless playback")
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 14
            font.weight: Font.Medium
        }

        SettingsHint {
            text: qsTr("Connect your own Qobuz subscription to unlock the MAX streaming quality: lossless and Hi-Res up to 24-bit/192 kHz. YouTube Music stays the catalog, and songs Qobuz can't match play from YouTube. Adaptive mix is unavailable while MAX is on.")
        }
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: 1
        color: "#12ffffff"
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 20

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4

            Text {
                text: qsTr("Account")
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: Font.Medium
            }

            SettingsHint {
                text: root.status === "restoring" ? qsTr("Checking for a saved Qobuz connection...")
                    : root.status === "connecting" ? qsTr("Waiting for you to sign in to Qobuz in your browser.")
                    : root.status === "connected"
                        ? (root.active
                            ? qsTr("Connected. Streaming quality is MAX, so matching songs play from Qobuz.")
                            : qsTr("Connected. Choose MAX under Settings, Playback, Streaming quality to play from Qobuz."))
                        : qsTr("Connect a Qobuz account with an active subscription. This uses Qobuz's private web API, so it may break when Qobuz changes its web player.")
            }
        }

        SettingsButton {
            id: connectButton
            visible: root.status === "disconnected" || root.status === "restoring"
            enabled: root.status === "disconnected"
            highlighted: true
            text: qsTr("Connect Qobuz")
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

    SettingsHint {
        visible: root.message.length > 0
        text: root.message
        color: root.messageIsError ? "#e6a197" : "#8d968e"
    }
}
