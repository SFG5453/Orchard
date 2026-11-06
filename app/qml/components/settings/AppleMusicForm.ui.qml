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

// Apple Music connection rows; sits in a SettingsSection with rowSpacing and verticalPadding 0.
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
    property alias historySwitch: historySwitch
    spacing: 0

    SettingsRow {
        RowLayout {
            Layout.fillWidth: true
            spacing: 14

            Item {
                Layout.preferredWidth: 48
                Layout.preferredHeight: 48

                // Builds without the local logo show a plain tile.
                Rectangle {
                    anchors.fill: parent
                    visible: logo.status !== Image.Ready && svgLogo.status !== Image.Ready
                    radius: 10
                    color: "#fa2d48"

                    LucideIcon {
                        anchors.centerIn: parent
                        width: 24
                        height: 24
                        name: "music-2"
                        color: "#ffffff"
                    }
                }

                Image {
                    id: logo
                    anchors.fill: parent
                    source: "qrc:/brand/apple-music.png"
                    sourceSize: Qt.size(96, 96)
                    smooth: true
                }

                Image {
                    id: svgLogo
                    anchors.fill: parent
                    visible: logo.status !== Image.Ready
                    source: "qrc:/brand/apple-music.svg"
                    sourceSize: Qt.size(96, 96)
                    smooth: true
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4

                Text {
                    Layout.fillWidth: true
                    text: qsTr("Apple Music")
                    elide: Text.ElideRight
                    color: "#f0eee7"
                    font.family: "Inter"
                    font.pixelSize: 14
                }

                Text {
                    Layout.fillWidth: true
                    text: root.status === "restoring" ? qsTr("Checking for a saved connection...")
                        : root.status === "connecting" ? qsTr("Finish signing in in the Apple Music window.")
                        : root.connected ? qsTr("Connected")
                        : !root.loginAvailable ? qsTr("Sign-in is unavailable in this build.")
                        : !root.accountSignedIn ? qsTr("Sign in to your Orchard account below first.")
                        : qsTr("Requires an Apple Music subscription.")
                    wrapMode: Text.WordWrap
                    color: "#a4aaa1"
                    font.family: "Inter"
                    font.pixelSize: 12
                }
            }

            SettingsButton {
                id: connectButton
                objectName: "settingsConnectAppleMusic"
                visible: root.status === "disconnected" || root.status === "restoring"
                enabled: root.status === "disconnected" && root.loginAvailable && root.accountSignedIn
                highlighted: true
                text: qsTr("Connect")
                Accessible.name: qsTr("Connect Apple Music")
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
                Accessible.name: qsTr("Disconnect Apple Music")
            }
        }
    }

    SettingsRow {
        visible: root.connected
        iconName: "clock"
        title: qsTr("Save plays to Apple Music history")
        description: qsTr("Songs you play in Orchard appear in Recently Played on your other Apple Music devices.")

        SettingsSwitch {
            id: historySwitch
            objectName: "settingsAppleMusicHistory"
            Accessible.name: qsTr("Send listening history to Apple Music")
        }
    }

    Text {
        Layout.fillWidth: true
        Layout.topMargin: 4
        Layout.bottomMargin: 14
        visible: root.message.length > 0
        text: root.message
        wrapMode: Text.WordWrap
        color: root.messageIsError ? "#e6a197" : "#8d968e"
        font.family: "Inter"
        font.pixelSize: 12
    }
}
