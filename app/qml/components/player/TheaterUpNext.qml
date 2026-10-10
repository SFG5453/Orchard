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
import QtQuick.Layouts

// Frosted "Up next" rail laid over the right edge of the picture.
Rectangle {
    id: root

    signal closeRequested()

    color: "#e00a0a0c"
    border.color: "#14ffffff"

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        anchors.topMargin: 20
        spacing: 8

        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: 8

            Label {
                Layout.fillWidth: true
                text: qsTr("UP NEXT")
                color: "#8d928a"
                font.pixelSize: 11
                font.weight: Font.DemiBold
                font.letterSpacing: 1.8
            }

            TheaterButton {
                glyph: "x"
                label: qsTr("Close up next")
                size: 36
                iconSize: 18
                onClicked: root.closeRequested()
            }
        }

        Label {
            visible: list.count === 0
            Layout.leftMargin: 8
            text: qsTr("Nothing queued.")
            color: "#8d928a"
            font.pixelSize: 14
        }

        ListView {
            id: list

            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 2
            model: OrchardPlayback.queue || []
            boundsBehavior: Flickable.StopAtBounds

            delegate: ItemDelegate {
                id: row

                required property var modelData
                required property int index

                width: ListView.view.width
                height: 88
                hoverEnabled: true
                enabled: !OrchardPlayback.loading
                Accessible.name: qsTr("Play %1").arg(modelData.title || "")
                onClicked: OrchardPlayback.playQueueIndex(index)

                background: Rectangle {
                    radius: 10
                    color: row.hovered ? "#0dffffff" : "transparent"
                    border.color: row.activeFocus ? "#f0eee7" : "transparent"
                }

                contentItem: RowLayout {
                    spacing: 14

                    Rectangle {
                        Layout.preferredWidth: 128
                        Layout.preferredHeight: 72
                        radius: 6
                        color: "#1b1d20"
                        clip: true

                        Image {
                            anchors.fill: parent
                            source: row.modelData.thumbnail || ""
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                            sourceSize.width: 256
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 4

                        Label {
                            Layout.fillWidth: true
                            text: row.modelData.title || ""
                            color: "#f7f5f0"
                            font.pixelSize: 14
                            font.weight: Font.DemiBold
                            elide: Text.ElideRight
                        }

                        Label {
                            Layout.fillWidth: true
                            text: (row.modelData.artists || []).join(", ") || row.modelData.artist || ""
                            color: "#8d928a"
                            font.pixelSize: 13
                            elide: Text.ElideRight
                        }
                    }
                }
            }
        }
    }
}
