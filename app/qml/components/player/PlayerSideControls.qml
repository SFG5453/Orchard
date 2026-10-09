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

// Orchard Connect, volume and the queue button.
RowLayout {
    // The player bar this lives in; it owns the colours and helpers.
    required property Item bar

    Layout.alignment: Qt.AlignVCenter
    spacing: 4

    Button {
        id: videoButton

        // OrchardMusicVideo is supplied as a QQmlContext property by main.cpp.
        // qmllint disable unqualified
        visible: OrchardMusicVideo.status !== "" && OrchardMusicVideo.status !== "unavailable"
        enabled: OrchardMusicVideo.available
        implicitWidth: bar.controlSize
        implicitHeight: bar.controlSize
        hoverEnabled: true
        focusPolicy: Qt.TabFocus
        Accessible.name: ToolTip.text
        ToolTip.visible: hovered
        ToolTip.delay: 500
        ToolTip.text: OrchardMusicVideo.status === "checking" ? qsTr("Finding music video") : qsTr("Watch music video")
        onClicked: OrchardMusicVideo.show()

        background: Rectangle {
            radius: width / 2
            color: videoButton.hovered ? "#1fffffff" : "transparent"
            border.color: videoButton.activeFocus ? bar.primaryText : "transparent"

            Behavior on color { ColorAnimation { duration: 90 } }
        }

        contentItem: LucideIcon {
            name: "video"
            color: bar.controlColor
            opacity: videoButton.enabled ? 1 : 0.4
            implicitWidth: 17
            implicitHeight: 17
        }
    }

    ConnectButton {
        backdrop: bar.backdrop
        accentColor: bar.accentColor
        controlColor: bar.controlColor
        focusColor: bar.primaryText
    }

    Button {
        id: volumeButton

        implicitWidth: bar.controlSize
        implicitHeight: bar.controlSize
        hoverEnabled: true
        focusPolicy: Qt.TabFocus
        Accessible.name: qsTr("Volume")
        ToolTip.visible: hovered
        ToolTip.delay: 500
        ToolTip.text: qsTr("Volume")

        background: Rectangle {
            radius: width / 2
            color: volumeButton.hovered ? "#1fffffff" : "transparent"
            border.color: volumeButton.activeFocus ? bar.primaryText : "transparent"

            Behavior on color { ColorAnimation { duration: 90 } }
        }

        contentItem: LucideIcon {
            // OrchardHome is supplied as a QQmlContext property by main.cpp.
            // qmllint disable unqualified
            name: bar.volumeLevel <= 0.001 ? "volume-x" : "volume-2"
            color: bar.controlColor
            implicitWidth: 17
            implicitHeight: 17
        }
    }

    Slider {
        id: volume

        visible: !bar.compact
        Layout.preferredWidth: bar.canopy ? 56 : 72
        from: 0
        to: 1
        stepSize: 0.01
        hoverEnabled: true
        value: bar.volumeLevel
        onMoved: bar.setVolumeLevel(value)

        // One wheel notch is 5%. Touchpads send fractional notches and glide smoothly.
        // Scrolling up for louder, as nature intended. Scrolling down is for regret.
        WheelHandler {
            target: null
            acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
            onWheel: function(event) {
                const delta = event.angleDelta.y !== 0 ? event.angleDelta.y : -event.angleDelta.x;
                const step = (event.inverted ? -delta : delta) / 120 * 0.05;
                bar.setVolumeLevel(Math.max(0, Math.min(1, bar.volumeLevel + step)));
            }
        }

        background: Item {
            x: volume.leftPadding
            y: volume.topPadding
            width: volume.availableWidth
            height: volume.availableHeight

            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width
                height: 3
                radius: height / 2
                color: bar.trackColor

                Rectangle {
                    width: volume.visualPosition * parent.width
                    height: parent.height
                    radius: parent.radius
                    color: bar.accentColor
                }
            }
        }

        handle: Rectangle {
            x: volume.leftPadding + volume.visualPosition * (volume.availableWidth - width)
            y: volume.topPadding + volume.availableHeight / 2 - height / 2
            width: 9
            height: 9
            radius: width / 2
            scale: volume.pressed ? 1.15 : volume.hovered ? 1 : 0.8
            color: bar.accentColor

            Behavior on scale {
                NumberAnimation { duration: 90; easing.type: Easing.OutCubic }
            }
        }
    }

    Button {
        id: queueButton

        implicitWidth: bar.controlSize
        implicitHeight: bar.controlSize
        hoverEnabled: true
        focusPolicy: Qt.TabFocus
        Accessible.name: qsTr("Queue")
        Accessible.checkable: true
        Accessible.checked: bar.queueOpen
        ToolTip.visible: hovered
        ToolTip.delay: 500
        ToolTip.text: qsTr("Queue")
        onClicked: bar.queueRequested()

        background: Rectangle {
            radius: width / 2
            color: bar.queueOpen ? bar.tint(bar.accentColor, queueButton.hovered ? 0.3 : 0.2) : queueButton.hovered ? "#1fffffff" : "transparent"
            border.color: queueButton.activeFocus ? bar.primaryText : "transparent"

            Behavior on color { ColorAnimation { duration: 90 } }
        }

        contentItem: LucideIcon {
            name: "list-music"
            color: bar.queueOpen ? bar.accentColor : bar.controlColor
            implicitWidth: 17
            implicitHeight: 17
        }
    }
}
