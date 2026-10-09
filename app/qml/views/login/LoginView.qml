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
import "../../components"

Item {
    id: root

    signal loginRequested
    signal troubleRequested

    anchors.fill: parent

    Column {
        id: centerBlock
        anchors.centerIn: parent
        spacing: 0

        // App Logo Icon Container (Dark squircle)
        Rectangle {
            id: logoCard
            anchors.horizontalCenter: parent.horizontalCenter
            width: 86
            height: 86
            radius: 22
            color: "#181b20"
            border.color: "#252932"
            border.width: 1

            Image {
                anchors.centerIn: parent
                width: 60
                height: 60
                source: "qrc:/qt/qml/Orchard/app/qml/assets/orchard-logo.png"
                fillMode: Image.PreserveAspectFit
                smooth: true
                mipmap: true
            }
        }

        Item {
            width: 1
            height: 26
        }

        // App Title
        Text {
            id: appTitle
            anchors.horizontalCenter: parent.horizontalCenter
            text: qsTr("Orchard")
            color: "#ffffff"
            font.pixelSize: 32
            font.weight: Font.Bold
            font.family: "Inter"
            font.letterSpacing: -0.5
        }

        Item {
            width: 1
            height: 8
        }

        // Subtitle
        Text {
            id: appSubtitle
            anchors.horizontalCenter: parent.horizontalCenter
            text: qsTr("Sign in to start listening")
            color: "#8a909a"
            font.pixelSize: 15
            font.family: "Inter"
        }

        Item {
            width: 1
            height: 32
        }

        GoogleSignInButton {
            id: signInBtn
            anchors.horizontalCenter: parent.horizontalCenter
            onClicked: root.loginRequested()
        }

        Item {
            width: 1
            height: 24
        }

        Text {
            width: Math.min(root.width - 48, 360)
            anchors.horizontalCenter: parent.horizontalCenter
            text: OrchardAuth.errorMessage
            visible: text.length > 0
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            color: "#e6a0a0"
            font.pixelSize: 13
            font.family: "Inter"
        }
    }
}
