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

import ".."
import QtQuick
import QtQuick.Layouts

// Orchard account sign-in, GitHub link and device list.
ColumnLayout {
    id: root

    // "signing_in", "restoring" or anything else for signed out.
    property string status: ""
    property bool signedIn: false
    readonly property bool signingIn: status === "signing_in"
    property string userName: ""
    property string userEmail: ""
    property string userPicture: ""
    // First letter shown while the picture loads.
    property string initial: "?"
    property bool githubLinked: false
    property bool githubLinking: false
    property string githubLogin: ""
    property var devices: []
    property bool devicesLoading: false
    property string errorMessage: ""
    property alias signInButton: signInButton
    property alias cancelSignInButton: cancelSignInButton
    property alias signOutButton: signOutButton
    property alias githubButton: githubButton
    property alias deviceList: deviceList

    spacing: 16

    Text {
        Layout.fillWidth: true
        visible: !root.signedIn
        text: root.signingIn
            ? qsTr("Finish signing in in your browser. Orchard is waiting patiently, like a good tree.")
            : root.status === "restoring"
                ? qsTr("Checking your Orchard account...")
                : qsTr("Sign in to use Orchard across your devices. This is separate from your YouTube Music account.")
        wrapMode: Text.WordWrap
        color: "#b8bab3"
        font.family: "Inter"
        font.pixelSize: 12
    }

    RowLayout {
        visible: !root.signedIn && root.status !== "restoring"
        spacing: 10

        SettingsButton {
            id: signInButton
            text: root.signingIn ? qsTr("Open browser again") : qsTr("Sign in with Google")
            highlighted: !root.signingIn
        }

        SettingsButton {
            id: cancelSignInButton
            visible: root.signingIn
            text: qsTr("Cancel")
        }
    }

    RowLayout {
        Layout.fillWidth: true
        visible: root.signedIn
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
                source: root.userPicture
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
                text: root.userName || qsTr("Orchard account")
                elide: Text.ElideRight
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 14
            }

            Text {
                Layout.fillWidth: true
                text: root.userEmail
                visible: text.length > 0
                elide: Text.ElideRight
                color: "#a4aaa1"
                font.family: "Inter"
                font.pixelSize: 12
            }
        }

        SettingsButton {
            id: signOutButton
            text: qsTr("Sign out")
        }
    }

    // Bug reports become GitHub issues in your name, so they need this link.
    RowLayout {
        Layout.fillWidth: true
        visible: root.signedIn
        spacing: 12

        LucideIcon {
            Layout.preferredWidth: 18
            Layout.preferredHeight: 18
            name: "github"
            color: "#c9ccc4"
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            Text {
                text: qsTr("GitHub")
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 13
            }

            Text {
                Layout.fillWidth: true
                text: root.githubLinked
                    ? qsTr("@%1 · Used for bug reports").arg(root.githubLogin)
                    : root.githubLinking
                        ? qsTr("Finish linking in your browser.")
                        : qsTr("Link GitHub to send bug reports from Orchard.")
                elide: Text.ElideRight
                color: "#a4aaa1"
                font.family: "Inter"
                font.pixelSize: 11
            }
        }

        SettingsButton {
            id: githubButton
            text: root.githubLinked ? qsTr("Unlink")
                : root.githubLinking ? qsTr("Cancel") : qsTr("Link GitHub")
        }
    }

    Text {
        visible: root.signedIn
        text: qsTr("Devices")
        color: "#f0eee7"
        font.family: "Inter"
        font.pixelSize: 13
        font.weight: Font.DemiBold
    }

    // Every place you've let Orchard take root.
    // The logic layer swaps in a delegate that formats details and removes devices.
    Repeater {
        id: deviceList
        model: root.signedIn ? root.devices : []

        delegate: OrchardDeviceRow {
            required property var modelData
            name: modelData.name
            detail: modelData.platform
            removable: !modelData.current
        }
    }

    Text {
        visible: root.signedIn && root.devicesLoading && root.devices.length === 0
        text: qsTr("Loading devices...")
        color: "#a4aaa1"
        font.family: "Inter"
        font.pixelSize: 12
    }

    Text {
        Layout.fillWidth: true
        visible: root.errorMessage.length > 0
        text: root.errorMessage
        wrapMode: Text.WordWrap
        color: "#e8a598"
        font.family: "Inter"
        font.pixelSize: 12
    }
}
