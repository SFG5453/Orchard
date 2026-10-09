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

// YouTube Music account card with switch and sign out buttons.
RowLayout {
    id: root

    property string userName: ""
    property string userHandle: ""
    property string userAvatar: ""
    // First letter shown while the avatar loads.
    property string initial: "?"
    property bool signedIn: true
    property bool switching: false
    property alias switchButton: switchButton
    property alias signOutButton: signOutButton

    spacing: 14

    Rectangle {
        Layout.preferredWidth: 48
        Layout.preferredHeight: 48
        radius: 10
        color: "#30362e"
        clip: true

        Text {
            anchors.centerIn: parent
            text: root.initial
            color: "#f0eee7"
            font.pixelSize: 20
        }

        Image {
            anchors.fill: parent
            source: root.userAvatar
            sourceSize: Qt.size(96, 96)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
        }
    }

    ColumnLayout {
        Layout.fillWidth: true
        spacing: 4

        Text {
            Layout.fillWidth: true
            text: root.userName || qsTr("YouTube account")
            elide: Text.ElideRight
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 14
        }

        Text {
            Layout.fillWidth: true
            text: root.userHandle
            visible: text.length > 0
            elide: Text.ElideRight
            color: "#a4aaa1"
            font.family: "Inter"
            font.pixelSize: 12
        }
    }

    // Brand channels and other Google accounts, without signing out first.
    SettingsButton {
        id: switchButton
        text: root.switching ? qsTr("Choosing...") : qsTr("Switch account")
        enabled: root.signedIn && !root.switching
        Accessible.name: qsTr("Switch YouTube account")
    }

    SettingsButton {
        id: signOutButton
        text: qsTr("Sign out")
    }
}
