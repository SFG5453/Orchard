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

// One queue entry: drag handle, cover, title, tempo and remove.
Item {
    id: rowItem
    required property var panel
    required property ListView list

    required property var modelData
    required property int index
    readonly property bool hot: song.hovered || song.activeFocus || handleArea.containsMouse || removeBtn.hovered
    readonly property bool mixingIn: OrchardPlayback.crossfadeActive && index === 0
                                     && modelData.id === OrchardPlayback.transitionTrack.id
    // Rows cascade in behind the panel slide; later rows wait their turn.
    readonly property real enter: Math.max(0, Math.min(1, panel.reveal * 2.2 - Math.min(index, 10) * 0.12))

    width: list.width
    height: 56
    opacity: (panel.dragIndex === index ? 0.35 : 1.0) * enter
    transform: Translate { y: (1 - rowItem.enter) * 14 }

    // Drop target indicator line
    Rectangle {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 8
        anchors.rightMargin: 8
        height: 2
        color: panel.accentColor
        radius: 1
        visible: panel.dragIndex >= 0 && panel.dropIndex === rowItem.index
        z: 5
    }

    Rectangle {
        anchors.fill: parent
        radius: 12
        color: rowItem.hot ? "#14ffffff" : rowItem.index === 0 ? panel.tint(panel.accentColor, 0.07) : "transparent"
        border.color: song.activeFocus ? panel.accentColor : "transparent"
        Behavior on color { ColorAnimation { duration: 120 } }
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 2
        anchors.rightMargin: 8
        spacing: 2

        // Drag handle. Braille dots were cute until someone tried to read War and Peace with their cursor.
        Item {
            id: dragHandle
            objectName: "queueDragHandle"
            Layout.preferredWidth: 18
            Layout.fillHeight: true
            Accessible.name: qsTr("Drag to reorder %1").arg(rowItem.modelData.title)

            Grid {
                anchors.centerIn: parent
                columns: 2
                spacing: 3
                opacity: handleArea.pressed ? 1 : rowItem.hot ? 0.7 : 0
                Behavior on opacity { NumberAnimation { duration: 140 } }

                Repeater {
                    model: 6
                    Rectangle {
                        width: 2.5
                        height: 2.5
                        radius: 1.25
                        color: handleArea.pressed ? panel.accentColor : panel.secondaryText
                    }
                }
            }

            MouseArea {
                id: handleArea
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
                preventStealing: true

                onPressed: function(mouse) {
                    panel.dragIndex = rowItem.index;
                    panel.dragY = mapToItem(list, mouse.x, mouse.y).y;
                    panel.updateDrop();
                }
                onPositionChanged: function(mouse) {
                    if (!pressed || panel.dragIndex < 0) return;
                    panel.dragY = mapToItem(list, mouse.x, mouse.y).y;
                    panel.updateDrop();
                }
                onReleased: {
                    const from = panel.dragIndex;
                    const to = panel.dropIndex;
                    panel.dragIndex = -1;
                    panel.dropIndex = -1;
                    OrchardPlayback.moveQueueItem(from, to);
                }
                onCanceled: {
                    panel.dragIndex = -1;
                    panel.dropIndex = -1;
                }
            }
        }

        Button {
            id: song
            objectName: "queuedSong"
            Layout.fillWidth: true
            Layout.fillHeight: true
            enabled: !OrchardPlayback.loading
            onClicked: OrchardPlayback.playQueueIndex(rowItem.index)
            Accessible.name: qsTr("Play %1").arg(rowItem.modelData.title)
            background: Item {}

            contentItem: RowLayout {
                spacing: 10

                Item {
                    Layout.preferredWidth: 40
                    Layout.preferredHeight: 40

                    RoundedArtwork {
                        anchors.fill: parent
                        radius: 8
                        source: rowItem.modelData.thumbnail || ""
                        scale: rowItem.hot ? 1.05 : 1
                        Behavior on scale { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                    }

                    // Play affordance fades over the cover on hover.
                    Rectangle {
                        anchors.fill: parent
                        radius: 8
                        color: "#99000000"
                        opacity: song.hovered ? 1 : 0
                        Behavior on opacity { NumberAnimation { duration: 140 } }

                        LucideIcon {
                            anchors.centerIn: parent
                            width: 16
                            height: 16
                            name: "play"
                            color: "#ffffff"
                        }
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 6

                        Rectangle {
                            visible: rowItem.index === 0
                            implicitWidth: nextLabel.implicitWidth + 10
                            implicitHeight: 15
                            radius: 4
                            color: panel.tint(panel.accentColor, 0.2)

                            Text {
                                id: nextLabel
                                anchors.centerIn: parent
                                text: rowItem.mixingIn ? qsTr("MIXING IN") : qsTr("NEXT")
                                color: panel.accentColor
                                font.family: "Inter"
                                font.pixelSize: 8
                                font.bold: true
                                font.letterSpacing: 0.6
                            }
                        }

                        Text {
                            objectName: "queuedSongTitle"
                            Layout.fillWidth: true
                            text: rowItem.modelData.title || ""
                            color: panel.primaryText
                            font.family: "Inter"
                            font.pixelSize: 12
                            font.bold: true
                            elide: Text.ElideRight
                        }

                        SlopBadge {
                            trackId: rowItem.modelData.id || ""
                        }
                    }

                    Text {
                        Layout.fillWidth: true
                        text: rowItem.modelData.artist || (rowItem.modelData.artists || []).join(", ")
                        color: panel.mutedText
                        font.family: "Inter"
                        font.pixelSize: 11
                        elide: Text.ElideRight
                    }
                }
            }
        }

        // Best Mix tempo, shown only while sorted. Restore order and the DJ packs up.
        Text {
            objectName: "queuedSongTempo"
            readonly property real bpm: OrchardPlayback.bestMixSorted
                                        ? Number(OrchardPlayback.bestMix.tempos[rowItem.modelData.id] || 0) : 0
            visible: bpm > 0
            Layout.preferredWidth: 50
            horizontalAlignment: Text.AlignRight
            text: qsTr("%1 BPM").arg(Math.round(bpm))
            color: panel.mutedText
            font.family: "Inter"
            font.pixelSize: 10
            font.features: { "tnum": 1 }
            Accessible.name: qsTr("%1 beats per minute").arg(Math.round(bpm))
        }

        // Duration and remove share a slot; hover swaps one for the other.
        Item {
            Layout.preferredWidth: 34
            Layout.fillHeight: true

            Text {
                anchors.centerIn: parent
                text: panel.clock(panel.seconds(rowItem.modelData))
                color: panel.mutedText
                font.family: "Inter"
                font.pixelSize: 11
                font.features: { "tnum": 1 }
                opacity: rowItem.hot ? 0 : 1
                Behavior on opacity { NumberAnimation { duration: 120 } }
            }

            Button {
                id: removeBtn
                objectName: "removeQueuedSong"
                anchors.centerIn: parent
                implicitWidth: 28
                implicitHeight: 28
                Accessible.name: qsTr("Remove %1 from queue").arg(rowItem.modelData.title)
                onClicked: OrchardPlayback.removeFromQueue(rowItem.index)
                opacity: rowItem.hot || removeBtn.activeFocus ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 120 } }

                background: Rectangle {
                    radius: height / 2
                    color: removeBtn.down ? "#4a2622" : removeBtn.hovered ? "#3a201d" : "transparent"
                    border.color: removeBtn.activeFocus ? "#e6a197" : "transparent"
                }
                contentItem: Item {
                    LucideIcon {
                        anchors.centerIn: parent
                        width: 14
                        height: 14
                        name: "x"
                        color: removeBtn.hovered ? "#e6a197" : panel.secondaryText
                    }
                }
            }
        }
    }
}
