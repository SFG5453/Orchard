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
import "../home"
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Autoplay toggle with load and error state.
Rectangle {
    id: autoplayCard
    required property var panel

    Layout.fillWidth: true
    Layout.preferredHeight: implicitHeight * (1 - panel.lyricsReveal)
    Layout.topMargin: -12 * panel.lyricsReveal
    visible: panel.lyricsReveal < 0.999
    opacity: 1 - panel.lyricsReveal
    clip: true
    implicitHeight: autoplayLayout.implicitHeight + 20
    radius: 14
    color: "#10ffffff"
    border.color: "#1affffff"

    RowLayout {
        id: autoplayLayout
        anchors.fill: parent
        anchors.margins: 10
        spacing: 10

        Rectangle {
            Layout.preferredWidth: 34
            Layout.preferredHeight: 34
            radius: 10
            color: OrchardPlayback.autoplayEnabled ? panel.tint(panel.accentColor, 0.18) : "#14ffffff"
            Behavior on color { ColorAnimation { duration: 200 } }

            LucideIcon {
                anchors.centerIn: parent
                name: "infinity"
                width: 18
                height: 18
                color: OrchardPlayback.autoplayEnabled ? panel.accentColor : panel.mutedText
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            Text {
                text: qsTr("Autoplay")
                color: panel.primaryText
                font.family: "Inter"
                font.pixelSize: 12
                font.bold: true
            }

            Text {
                Layout.fillWidth: true
                text: OrchardPlayback.autoplayLoading ? qsTr("Finding more music…")
                      : OrchardPlayback.autoplayError || qsTr("Keep the music going")
                color: OrchardPlayback.autoplayError ? "#e6a197" : panel.mutedText
                wrapMode: Text.Wrap
                font.family: "Inter"
                font.pixelSize: 10
            }

            Button {
                id: retryAutoplay
                visible: Boolean(OrchardPlayback.autoplayError)
                text: qsTr("Retry")
                implicitHeight: 22
                leftPadding: 8
                rightPadding: 8
                onClicked: OrchardPlayback.retryAutoplay()
                background: Rectangle {
                    radius: 6
                    color: retryAutoplay.hovered ? "#26ffffff" : "#14ffffff"
                }
                contentItem: Text {
                    text: retryAutoplay.text
                    color: panel.primaryText
                    font.family: "Inter"
                    font.pixelSize: 11
                    font.bold: true
                    verticalAlignment: Text.AlignVCenter
                }
            }
        }

        Switch {
            id: autoplaySwitch
            objectName: "queueAutoplay"
            checked: OrchardPlayback.autoplayEnabled
            Accessible.name: qsTr("Autoplay")
            onToggled: OrchardPlayback.autoplayEnabled = checked

            indicator: Rectangle {
                implicitWidth: 42
                implicitHeight: 24
                x: autoplaySwitch.leftPadding
                y: autoplaySwitch.topPadding + autoplaySwitch.availableHeight / 2 - height / 2
                radius: 12
                color: autoplaySwitch.checked ? panel.tint(panel.accentColor, 0.55) : "#26ffffff"
                border.color: autoplaySwitch.activeFocus ? panel.primaryText : "transparent"
                Behavior on color { ColorAnimation { duration: 150 } }

                Rectangle {
                    x: autoplaySwitch.checked ? parent.width - width - 3 : 3
                    y: 3
                    width: 18
                    height: 18
                    radius: 9
                    color: autoplaySwitch.checked ? "#ffffff" : "#a8ada4"

                    Behavior on x {
                        NumberAnimation { duration: 150; easing.type: Easing.OutCubic }
                    }
                }
            }
        }
    }
}
