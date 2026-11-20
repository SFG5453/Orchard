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
import QtQuick.Controls

// Vertical slider that opens toward the page from the player bar.
Popup {
    id: root

    // The player bar owns the colours and the volume helpers.
    required property Item bar

    width: 44
    height: 164
    padding: 0
    x: (parent.width - width) / 2
    y: bar.canopy ? parent.height + 8 : (parent.height - bar.height) / 2 - height - 8
    // Presses on the volume button toggle the popup instead of dismissing it.
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent

    enter: Transition {
        NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 120; easing.type: Easing.OutCubic }
    }

    exit: Transition {
        NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 100; easing.type: Easing.InCubic }
    }

    background: Rectangle {
        radius: 14
        color: Qt.alpha(OrchardAppearance.popupColor, 0.95)
        border.color: "#1cffffff"
        border.width: 1
    }

    contentItem: Item {
        Slider {
            id: slider

            anchors.fill: parent
            anchors.topMargin: 14
            anchors.bottomMargin: 14
            orientation: Qt.Vertical
            from: 0
            to: 1
            stepSize: 0.01
            hoverEnabled: true
            value: root.bar.volumeLevel
            onMoved: root.bar.setVolumeLevel(value)
            Accessible.name: qsTr("Volume")

            // One wheel notch is 5%.
            WheelHandler {
                target: null
                acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
                onWheel: function(event) {
                    const step = (event.inverted ? -event.angleDelta.y : event.angleDelta.y) / 120 * 0.05;
                    root.bar.setVolumeLevel(Math.max(0, Math.min(1, root.bar.volumeLevel + step)));
                }
            }

            background: Item {
                x: slider.leftPadding
                y: slider.topPadding
                width: slider.availableWidth
                height: slider.availableHeight

                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: 4
                    height: parent.height
                    radius: width / 2
                    color: root.bar.trackColor

                    Rectangle {
                        anchors.bottom: parent.bottom
                        width: parent.width
                        height: slider.position * parent.height
                        radius: parent.radius
                        color: root.bar.accentColor
                    }
                }
            }

            handle: Rectangle {
                x: slider.leftPadding + slider.availableWidth / 2 - width / 2
                y: slider.topPadding + slider.visualPosition * (slider.availableHeight - height)
                width: 12
                height: 12
                radius: width / 2
                scale: slider.pressed ? 1.15 : 1
                color: root.bar.accentColor

                Behavior on scale {
                    NumberAnimation { duration: 90; easing.type: Easing.OutCubic }
                }
            }
        }
    }
}
