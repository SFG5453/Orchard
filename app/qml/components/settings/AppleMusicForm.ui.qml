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

ColumnLayout {
    id: root
    property string status: "disconnected"
    property bool connected: false
    property bool loginAvailable: true
    property bool accountSignedIn: false
    property string message: ""
    property bool messageIsError: false
    property alias connectButton: connectButton
    property alias cancelButton: cancelButton
    property alias disconnectButton: disconnectButton
    spacing: 20

    SettingsHint {
        text: qsTr("Artist pages, recommendations and playback from Apple Music. Pick Apple as the Music source in General to use it app-wide.")
    }

    RowLayout {
        Layout.fillWidth: true
        spacing: 20

        SettingsHint {
            Layout.fillWidth: true
            text: root.status === "restoring" ? qsTr("Checking for a saved Apple Music connection...")
                : root.status === "connecting" ? qsTr("Finish signing in in the Apple Music window.")
                : root.connected ? qsTr("Connected to Apple Music.")
                : !root.loginAvailable ? qsTr("Apple Music sign-in is unavailable in this build.")
                : !root.accountSignedIn ? qsTr("Sign in to your Orchard account under General first.")
                : qsTr("An Apple Music subscription is required.")
        }

        SettingsButton {
            id: connectButton
            objectName: "settingsConnectAppleMusic"
            visible: root.status === "disconnected" || root.status === "restoring"
            enabled: root.status === "disconnected" && root.loginAvailable && root.accountSignedIn
            highlighted: true
            text: qsTr("Connect Apple Music")
        }

        SettingsButton {
            id: cancelButton
            visible: root.status === "connecting"
            text: qsTr("Cancel")
        }

        SettingsButton {
            id: disconnectButton
            objectName: "settingsDisconnectAppleMusic"
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
