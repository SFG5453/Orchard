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

    property bool trayAvailable: true
    property bool signedIn: true
    property string authError: ""
    property alias persistSwitch: persistSwitch
    property alias windowSwitch: windowSwitch
    property alias traySwitch: traySwitch
    property alias historySwitch: historySwitch
    property alias account: account
    property bool updatesAvailable: true
    property string updateTitle: qsTr("Orchard is up to date")
    property string updateDetail: qsTr("Version 4.2.14 on the stable channel")
    property string updateActionText: qsTr("Check for updates")
    property bool updateActionEnabled: true
    property bool updateBusy: false
    property real updateProgress: 0
    property alias updateButton: updateButton
    property alias autoUpdateSwitch: autoUpdateSwitch
    property alias canarySwitch: canarySwitch

    spacing: 28
    Layout.fillWidth: true

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Launch")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "history"
            title: qsTr("Restore last session")
            description: qsTr("Pick up the last queue, song, position, and page when Orchard starts.")

            SettingsSwitch {
                id: persistSwitch
                objectName: "settingsPlaybackPersistence"
                Accessible.name: qsTr("Save queue, current song, and page")
            }
        }

        SettingsRow {
            iconName: "app-window"
            title: qsTr("Remember window size")
            description: qsTr("Reopen Orchard at the size you last used it.")

            SettingsSwitch {
                id: windowSwitch
                objectName: "settingsRememberWindow"
                Accessible.name: qsTr("Remember window size")
            }
        }

        SettingsRow {
            visible: root.trayAvailable
            iconName: "panel-bottom"
            title: qsTr("Keep playing in the system tray")
            description: qsTr("Closing the window leaves Orchard running in the tray.")

            SettingsSwitch {
                id: traySwitch
                objectName: "settingsCloseToTray"
                Accessible.name: qsTr("Close to system tray")
            }
        }
    }

    // Only installs managed by the Orchard bootstrapper can update themselves.
    SettingsSection {
        Layout.fillWidth: true
        visible: root.updatesAvailable
        title: qsTr("Updates")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            iconName: "refresh-cw"
            title: root.updateTitle
            description: root.updateDetail

            SettingsButton {
                id: updateButton
                objectName: "settingsUpdateAction"
                text: root.updateActionText
                enabled: root.updateActionEnabled
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.bottomMargin: 12
            visible: root.updateBusy
            implicitHeight: 4
            radius: 2
            color: "#1f2329"

            Rectangle {
                width: parent.width * root.updateProgress
                height: parent.height
                radius: 2
                color: "#7fbe90"
            }
        }

        SettingsRow {
            iconName: "download"
            title: qsTr("Download updates automatically")
            description: qsTr("Orchard fetches new versions in the background and installs them the next time it starts.")

            SettingsSwitch {
                id: autoUpdateSwitch
                objectName: "settingsAutoUpdate"
                Accessible.name: qsTr("Download updates automatically")
            }
        }

        SettingsRow {
            iconName: "sparkles"
            title: qsTr("Canary builds")
            description: qsTr("Get new features early. Canary builds are less tested.")

            SettingsSwitch {
                id: canarySwitch
                objectName: "settingsCanaryChannel"
                Accessible.name: qsTr("Use canary builds")
            }
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("YouTube Music")
        rowSpacing: 0
        verticalPadding: 0

        SettingsRow {
            AccountSetting {
                id: account
                Layout.fillWidth: true
            }
        }

        SettingsRow {
            iconName: "clock"
            title: qsTr("Save plays to YouTube Music history")
            description: qsTr("Songs you play in Orchard are added to your YouTube Music history.")

            SettingsSwitch {
                id: historySwitch
                objectName: "settingsYouTubeHistory"
                Accessible.name: qsTr("Send listening history to YouTube")
            }
        }

        Text {
            Layout.fillWidth: true
            Layout.topMargin: 4
            Layout.bottomMargin: 14
            visible: root.signedIn && root.authError.length > 0
            text: root.authError
            wrapMode: Text.WordWrap
            color: "#e6a197"
            font.family: "Inter"
            font.pixelSize: 12
        }
    }

    SettingsSection {
        Layout.fillWidth: true
        title: qsTr("Orchard Account")

        OrchardAccountSetting {
            Layout.fillWidth: true
        }
    }
}
