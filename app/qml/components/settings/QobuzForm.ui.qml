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
    property bool enteringToken: false
    // A token check is running, so the browser hint does not apply.
    property bool tokenPending: false
    property alias connectButton: connectButton
    property alias tokenToggleButton: tokenToggleButton
    property alias userIdField: userIdField
    property alias tokenField: tokenField
    property alias appIdField: appIdField
    property alias appSecretField: appSecretField
    property alias tokenSaveButton: tokenSaveButton
    property alias cancelButton: cancelButton
    property alias disconnectButton: disconnectButton

    spacing: 16

    SettingsHint {
        text: qsTr("Lossless and Hi-Res playback (up to 24-bit/192 kHz) with the MAX streaming quality. Adaptive mix is unavailable while MAX is on.")
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 20

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4

            SettingsHint {
                text: root.status === "restoring" ? qsTr("Checking for a saved Qobuz connection...")
                    : root.status === "connecting"
                        ? (root.tokenPending ? qsTr("Checking the Qobuz token...")
                            : qsTr("Waiting for you to sign in to Qobuz in your browser."))
                    : root.status === "connected"
                        ? (root.active
                            ? qsTr("Connected. Streaming quality is MAX, so matching songs play from Qobuz.")
                            : qsTr("Connected. Choose MAX under Settings, Playback, Streaming quality to play from Qobuz."))
                        : qsTr("Connect a Qobuz account with an active subscription.")
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
            id: tokenToggleButton
            visible: root.status === "disconnected"
            text: root.enteringToken ? qsTr("Cancel") : qsTr("Use a token")
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

    ColumnLayout {
        Layout.fillWidth: true
        visible: root.enteringToken && root.status === "disconnected"
        spacing: 8

        SettingsHint {
            text: qsTr("Signed in at play.qobuz.com, the browser's local storage entry \"localuser\" holds your user ID and auth token.")
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            SettingsTextField {
                id: userIdField
                Layout.preferredWidth: 160
                Layout.fillWidth: false
                inputMethodHints: Qt.ImhDigitsOnly
                placeholderText: qsTr("User ID")
                Accessible.name: qsTr("Qobuz user ID")
            }

            SettingsTextField {
                id: tokenField
                echoMode: TextInput.Password
                placeholderText: qsTr("Paste the auth token")
                Accessible.name: qsTr("Qobuz auth token")
            }
        }

        SettingsHint {
            text: qsTr("Optional: with your own app ID and secret, Orchard streams FLAC files from the Qobuz API (track/getFileUrl). The token must come from the same app.")
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            SettingsTextField {
                id: appIdField
                Layout.preferredWidth: 160
                Layout.fillWidth: false
                inputMethodHints: Qt.ImhDigitsOnly
                placeholderText: qsTr("App ID")
                Accessible.name: qsTr("Qobuz app ID")
            }

            SettingsTextField {
                id: appSecretField
                echoMode: TextInput.Password
                placeholderText: qsTr("App secret")
                Accessible.name: qsTr("Qobuz app secret")
            }

            SettingsButton {
                id: tokenSaveButton
                text: qsTr("Sign in")
            }
        }
    }

    SettingsHint {
        visible: root.message.length > 0
        text: root.message
        color: root.messageIsError ? "#e6a197" : "#8d968e"
    }
}
