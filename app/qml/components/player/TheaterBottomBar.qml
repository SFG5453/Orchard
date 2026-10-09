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
import Orchard
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window

// Lower letterbox bar: scrubber on the picture edge, then title, transport and utilities.
Item {
    id: root

    property color accentColor: "#f0eee7"
    readonly property bool menuOpen: qualityMenu.visible

    readonly property var track: OrchardPlayback.track || ({})
    readonly property bool windowFullScreen: Window.window !== null && Window.window.visibility === Window.FullScreen

    // Heights the current video offers; a fixed ladder until the first resolve reports them.
    readonly property var heightOptions: (OrchardMusicVideo.heights || []).length
        ? OrchardMusicVideo.heights : [2160, 1440, 1080, 720, 480, 360]

    component QualityItem: MenuItem {
        id: item

        implicitWidth: 200
        implicitHeight: 36
        checkable: true

        contentItem: Row {
            spacing: 10
            leftPadding: 4

            LucideIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: "check"
                color: "#f0eee7"
                width: 14
                height: 14
                opacity: item.checked ? 1 : 0
            }

            Label {
                anchors.verticalCenter: parent.verticalCenter
                text: item.text
                color: "#f7f5f0"
                font.pixelSize: 13
            }
        }

        background: Rectangle {
            radius: 8
            color: item.highlighted ? "#1fffffff" : "transparent"
        }
    }

    function heightLabel(height) {
        return height >= 2160 ? qsTr("4K") : height + "p";
    }

    function timeLabel(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0)
            return "0:00";
        const total = Math.floor(seconds);
        return Math.floor(total / 60) + ":" + String(total % 60).padStart(2, "0");
    }

    Slider {
        id: scrubber

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.leftMargin: 40
        anchors.rightMargin: 40
        anchors.topMargin: -9
        height: 18
        from: 0
        to: Math.max(1, OrchardPlayback.duration)
        enabled: OrchardPlayback.duration > 0
        hoverEnabled: true
        Accessible.name: qsTr("Seek")
        // Holds the drag position until release; seeking per pixel would thrash the stream.
        onPressedChanged: if (!pressed) OrchardPlayback.seek(value)

        Binding on value {
            value: OrchardPlayback.position
            when: !scrubber.pressed
        }

        background: Rectangle {
            x: scrubber.leftPadding
            y: scrubber.topPadding + scrubber.availableHeight / 2 - height / 2
            width: scrubber.availableWidth
            height: scrubber.hovered || scrubber.pressed ? 5 : 3
            radius: height / 2
            color: "#2effffff"

            Rectangle {
                width: scrubber.visualPosition * parent.width
                height: parent.height
                radius: parent.radius
                color: root.accentColor
            }

            // SponsorBlock spans, drawn over the progress so they read in both halves.
            Repeater {
                model: OrchardPlayback.skipSegments || []

                Rectangle {
                    required property var modelData

                    x: parent.width * modelData.startTime / scrubber.to
                    width: Math.max(2, parent.width * (modelData.endTime - modelData.startTime) / scrubber.to)
                    height: parent.height
                    color: modelData.category === "music_offtopic" ? "#ff9f43" : "#00d1a0"
                    opacity: 0.85
                }
            }

            Behavior on height { NumberAnimation { duration: 90 } }
        }

        handle: Rectangle {
            x: scrubber.leftPadding + scrubber.visualPosition * (scrubber.availableWidth - width)
            y: scrubber.topPadding + scrubber.availableHeight / 2 - height / 2
            width: 12
            height: 12
            radius: 6
            color: root.accentColor
            scale: scrubber.pressed ? 1.2 : scrubber.hovered ? 1 : 0.85

            Behavior on scale { NumberAnimation { duration: 90 } }
        }
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 40
        anchors.rightMargin: 40
        anchors.topMargin: 14
        spacing: 24

        ColumnLayout {
            Layout.fillWidth: true
            Layout.preferredWidth: 1
            Layout.alignment: Qt.AlignVCenter
            spacing: 6

            Label {
                Layout.fillWidth: true
                text: root.track.title || ""
                color: "#f7f5f0"
                font.pixelSize: 32
                font.weight: Font.Bold
                font.letterSpacing: -0.6
                elide: Text.ElideRight
            }

            Label {
                Layout.fillWidth: true
                text: root.track.artist || ""
                color: "#c3c6bf"
                font.pixelSize: 15
                elide: Text.ElideRight
            }
        }

        RowLayout {
            Layout.alignment: Qt.AlignVCenter
            spacing: 20

            TheaterButton {
                glyph: "skip-back"
                label: qsTr("Previous")
                size: 48
                iconSize: 22
                enabled: OrchardPlayback.canGoPrevious
                onClicked: OrchardPlayback.previous()
            }

            TheaterButton {
                glyph: OrchardPlayback.playing ? "pause" : "play"
                label: OrchardPlayback.playing ? qsTr("Pause") : qsTr("Play")
                size: 64
                iconSize: 24
                fill: root.accentColor
                iconColor: "#0b0d0a"
                onClicked: OrchardPlayback.toggle()
            }

            TheaterButton {
                glyph: "skip-forward"
                label: qsTr("Next")
                size: 48
                iconSize: 22
                enabled: OrchardPlayback.canGoNext
                onClicked: OrchardPlayback.next()
            }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredWidth: 1
            Layout.alignment: Qt.AlignVCenter
            spacing: 6

            Item { Layout.fillWidth: true }

            Label {
                Layout.rightMargin: 10
                text: root.timeLabel(scrubber.pressed ? scrubber.value : OrchardPlayback.position)
                      + "  /  " + root.timeLabel(OrchardPlayback.duration)
                color: "#c3c6bf"
                font.pixelSize: 13
                font.features: { "tnum": 1 }
            }

            LikeButton {
                implicitWidth: 44
                implicitHeight: 44
                iconSize: 20
                iconColor: "#f7f5f0"
            }

            Button {
                id: qualityButton

                implicitHeight: 36
                hoverEnabled: true
                focusPolicy: Qt.TabFocus
                Accessible.name: qsTr("Video quality")
                ToolTip.visible: hovered && !qualityMenu.visible
                ToolTip.delay: 500
                ToolTip.text: qsTr("Video quality")
                onClicked: qualityMenu.visible ? qualityMenu.close() : qualityMenu.open()

                background: Rectangle {
                    radius: 18
                    color: qualityButton.hovered || qualityMenu.visible ? "#1fffffff" : "#0fffffff"
                    border.color: qualityButton.activeFocus ? root.accentColor : "#14ffffff"
                }

                contentItem: Label {
                    text: OrchardMusicVideo.height > 0 ? root.heightLabel(OrchardMusicVideo.height) : qsTr("Quality")
                    color: "#f7f5f0"
                    font.pixelSize: 13
                    font.weight: Font.DemiBold
                    font.features: { "tnum": 1 }
                    leftPadding: 12
                    rightPadding: 12
                    verticalAlignment: Text.AlignVCenter
                }

                Menu {
                    id: qualityMenu

                    y: -implicitHeight - 8
                    x: qualityButton.width - width

                    padding: 6

                    background: Rectangle {
                        implicitWidth: 212
                        radius: 12
                        color: "#ee141518"
                        border.color: "#1fffffff"
                    }

                    QualityItem {
                        text: qsTr("Best available")
                        checked: OrchardMusicVideo.maxHeight === 0
                        onTriggered: OrchardMusicVideo.maxHeight = 0
                    }

                    Repeater {
                        model: root.heightOptions

                        QualityItem {
                            required property int modelData

                            text: root.heightLabel(modelData)
                            checked: OrchardMusicVideo.maxHeight === modelData
                            onTriggered: OrchardMusicVideo.maxHeight = modelData
                        }
                    }
                }
            }

            TheaterButton {
                glyph: root.windowFullScreen ? "minimize" : "maximize"
                label: root.windowFullScreen ? qsTr("Exit full screen") : qsTr("Full screen")
                onClicked: root.windowFullScreen ? Window.window.showNormal() : Window.window.showFullScreen()
            }
        }
    }
}
