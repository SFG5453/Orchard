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

// Orchard account card. Layout lives in OrchardAccountSettingForm.ui.qml.
OrchardAccountSettingForm {
    id: root

    status: OrchardAccount.status
    signedIn: OrchardAccount.isSignedIn
    userName: OrchardAccount.userName
    userEmail: OrchardAccount.userEmail
    userPicture: OrchardAccount.userPicture || ""
    initial: (OrchardAccount.userName || OrchardAccount.userEmail || "?").charAt(0)
    githubLinked: OrchardSupport.githubLinked
    githubLinking: OrchardSupport.githubLinking
    githubLogin: OrchardSupport.githubLogin
    devices: OrchardAccount.devices
    devicesLoading: OrchardAccount.devicesLoading
    errorMessage: OrchardAccount.errorMessage

    function platformLabel(platform) {
        switch (platform) {
        case "linux": return qsTr("Linux");
        case "windows": return qsTr("Windows");
        case "macos": return qsTr("macOS");
        case "android": return qsTr("Android");
        case "ios": return qsTr("iOS");
        default: return qsTr("Unknown");
        }
    }

    onSignedInChanged: if (signedIn) OrchardAccount.refreshDevices()
    Component.onCompleted: if (signedIn) OrchardAccount.refreshDevices()

    signInButton.onClicked: {
        if (root.signingIn)
            OrchardAccount.cancelSignIn();
        OrchardAccount.signIn();
    }
    cancelSignInButton.onClicked: OrchardAccount.cancelSignIn()
    signOutButton.onClicked: OrchardAccount.signOut()
    githubButton.onClicked: {
        if (OrchardSupport.githubLinked)
            OrchardSupport.unlinkGithub();
        else if (OrchardSupport.githubLinking)
            OrchardSupport.cancelGithubLink();
        else
            OrchardSupport.linkGithub();
    }

    deviceList.delegate: OrchardDeviceRow {
        required property var modelData
        name: modelData.name
        detail: modelData.current
            ? qsTr("%1 · This device").arg(root.platformLabel(modelData.platform))
            : qsTr("%1 · Last seen %2").arg(root.platformLabel(modelData.platform))
                .arg(Qt.formatDate(modelData.lastSeen, Locale.ShortFormat))
        removable: !modelData.current
        removeButton.onClicked: OrchardAccount.removeDevice(modelData.id)
    }
}
