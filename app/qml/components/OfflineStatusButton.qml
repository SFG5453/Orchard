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

import Orchard
import QtQuick
import QtQuick.Controls

// Top bar pill for the retry window and offline mode; clicking it retries.
Button {
    id: root

    readonly property bool shown: OrchardNetwork.offline || OrchardNetwork.retrying

    visible: shown
    width: shown ? label.implicitWidth + 50 : 0
    height: 34
    hoverEnabled: true
    Accessible.name: label.text
    ToolTip.visible: hovered
    ToolTip.text: OrchardNetwork.offline
        ? qsTr("You're offline. Showing your downloads. Click to try again.")
        : qsTr("The connection dropped. Click to check now.")
    onClicked: OrchardNetwork.retry()

    background: Rectangle {
        radius: height / 2
        color: root.hovered ? "#2effcf8a" : "#1effcf8a"
        border.color: root.activeFocus ? "#ffe0b0" : "#44ffcf8a"
        Behavior on color { ColorAnimation { duration: Motion.fast } }
    }
    contentItem: Row {
        spacing: 8
        anchors.centerIn: parent

        LucideIcon {
            anchors.verticalCenter: parent.verticalCenter
            width: 15
            height: 15
            name: "wifi-off"
            color: "#ffd9a3"
        }
        Text {
            id: label
            anchors.verticalCenter: parent.verticalCenter
            text: OrchardNetwork.offline ? qsTr("Offline") : qsTr("Retrying in %1s").arg(OrchardNetwork.retryIn)
            color: "#ffe4bd"
            font.family: "Inter"
            font.pixelSize: 12
            font.weight: Font.DemiBold
            font.features: { "tnum": 1 }
        }
    }
}
