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

// Permanent record of every guilty pleasure. Proceed accordingly.
ColumnLayout {
    id: root

    // "disconnected", "restoring", "authorizing", "pending", "completing" or "connected".
    property string status: "disconnected"
    property bool scrobbling: false
    property bool connected: false
    property string user: ""
    property string message: ""
    property bool messageIsError: false
    readonly property bool waitingForApproval: status === "pending" || status === "completing"
    property alias scrobbleSwitch: scrobbleSwitch
    property alias connectButton: connectButton
    property alias finishButton: finishButton
    property alias cancelButton: cancelButton
    property alias disconnectButton: disconnectButton

    spacing: 22

    RowLayout {
        Layout.fillWidth: true
        spacing: 20

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4

            Text {
                text: qsTr("Last.fm scrobbling")
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 14
                font.weight: Font.Medium
            }

            SettingsHint {
                text: qsTr("Send now-playing updates and finished listens to your Last.fm profile.")
            }
        }

        SettingsSwitch {
            id: scrobbleSwitch
            Layout.alignment: Qt.AlignVCenter
            padding: 0
            checked: root.scrobbling
            Accessible.name: qsTr("Enable Last.fm scrobbling")
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
                Layout.fillWidth: true
                text: root.connected ? root.user : qsTr("Account")
                elide: Text.ElideRight
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: Font.Medium
            }

            SettingsHint {
                text: root.status === "restoring" ? qsTr("Checking for a saved Last.fm connection...")
                    : root.status === "authorizing" ? qsTr("Opening Last.fm in your browser...")
                    : root.waitingForApproval ? qsTr("Approve Orchard on Last.fm, then finish the connection here.")
                    : root.status === "connected"
                        ? (root.scrobbling
                            ? qsTr("Scrobbling as %1.").arg(root.user)
                            : qsTr("Connected as %1. Scrobbling is paused.").arg(root.user))
                        : qsTr("Connect an account to add Orchard listening history to Last.fm.")
            }
        }

        SettingsButton {
            id: connectButton
            visible: root.status === "disconnected" || root.status === "authorizing"
            enabled: root.status === "disconnected"
            highlighted: true
            text: qsTr("Connect Last.fm")
        }

        SettingsButton {
            id: finishButton
            visible: root.waitingForApproval
            enabled: root.status === "pending"
            highlighted: true
            text: qsTr("Finish connection")
        }

        SettingsButton {
            id: cancelButton
            visible: root.waitingForApproval
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
