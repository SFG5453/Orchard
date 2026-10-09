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

// Explains offline mode on the pages it replaces, and offers the manual retry.
Rectangle {
    id: root

    property string detail: qsTr("Showing the music saved on this computer. Orchard stops retrying after a short wait, so reconnect and try again when you are ready.")

    implicitHeight: row.implicitHeight + 28
    radius: 14
    color: "#0affffff"
    border.color: "#1cffffff"
    visible: OrchardNetwork.offline

    Row {
        id: row
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        anchors.leftMargin: 16
        anchors.rightMargin: 16
        spacing: 14

        Rectangle {
            width: 40
            height: 40
            radius: 12
            anchors.verticalCenter: parent.verticalCenter
            color: "#14ffffff"

            LucideIcon {
                anchors.centerIn: parent
                width: 20
                height: 20
                name: "wifi-off"
                color: "#e6c9a1"
            }
        }

        Column {
            width: parent.width - 40 - retry.width - parent.spacing * 2
            anchors.verticalCenter: parent.verticalCenter
            spacing: 3

            Text {
                width: parent.width
                text: qsTr("You're offline")
                color: "#f2eee7"
                font.family: "Inter"
                font.pixelSize: 15
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }
            Text {
                width: parent.width
                text: root.detail
                color: "#a4adb1"
                font.family: "Inter"
                font.pixelSize: 12
                wrapMode: Text.Wrap
            }
        }

        Button {
            id: retry
            anchors.verticalCenter: parent.verticalCenter
            implicitWidth: retryRow.implicitWidth + 30
            implicitHeight: 34
            enabled: !OrchardNetwork.checking
            hoverEnabled: true
            Accessible.name: qsTr("Try to reconnect")
            onClicked: OrchardNetwork.retry()

            background: Rectangle {
                radius: height / 2
                color: retry.hovered ? "#f6f4ef" : "#f1efea"
                opacity: retry.enabled ? 1 : 0.6
                border.color: retry.activeFocus ? "#b8d4bd" : "transparent"
                border.width: 2
            }
            contentItem: Row {
                id: retryRow
                spacing: 8
                anchors.centerIn: parent

                LucideIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    width: 14
                    height: 14
                    name: "refresh-cw"
                    color: "#121212"

                    // Finishes its turn so the icon never stops crooked.
                    RotationAnimator on rotation {
                        from: 0
                        to: 360
                        duration: 900
                        loops: Animation.Infinite
                        running: OrchardNetwork.checking
                        alwaysRunToEnd: true
                    }
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: OrchardNetwork.checking ? qsTr("Checking…") : qsTr("Try again")
                    color: "#121212"
                    font.family: "Inter"
                    font.pixelSize: 13
                    font.weight: Font.DemiBold
                }
            }
        }
    }
}
