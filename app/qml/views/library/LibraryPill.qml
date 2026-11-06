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

import "../../components"
import Orchard
import QtQuick
import QtQuick.Controls

// Header actions and category filters.
Button {
    id: root

    property string iconName
    // Muted trailing text, e.g. an item count.
    property string detail
    property bool busy: false

    implicitHeight: 36
    implicitWidth: content.implicitWidth + (iconName ? 28 : 30)
    hoverEnabled: true

    background: Rectangle {
        radius: root.checkable ? 8 : height / 2
        color: root.checked ? "#d2e4d3"
            : root.down ? "#454b46"
            : root.hovered ? "#3a403b"
            : root.checkable ? "transparent" : "#303630"
        border.color: root.visualFocus ? OrchardAppearance.accentStrong
            : root.checkable && !root.checked ? "#7e8880" : "transparent"
        opacity: root.enabled ? 1 : 0.5
        Behavior on color { ColorAnimation { duration: Motion.normal } }
    }

    contentItem: Item {
        Row {
            id: content
            anchors.centerIn: parent
            spacing: 8

            LucideIcon {
                visible: root.iconName !== ""
                anchors.verticalCenter: parent.verticalCenter
                width: 14
                height: 14
                name: root.iconName
                color: root.checked ? "#17231a" : "#eceee9"

                // Finishes the turn it is on, so the icon never freezes crooked.
                RotationAnimator on rotation {
                    from: 0
                    to: 360
                    duration: 900
                    loops: Animation.Infinite
                    running: root.busy
                    alwaysRunToEnd: true
                }
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: root.text
                color: root.checked ? "#17231a" : "#eceee9"
                font.family: "Inter"
                font.pixelSize: 13
                Behavior on color { ColorAnimation { duration: Motion.normal } }
            }
            Text {
                visible: root.detail !== ""
                anchors.verticalCenter: parent.verticalCenter
                text: root.detail
                color: root.checked ? "#405044" : "#aeb6ae"
                font.family: "Inter"
                font.pixelSize: 12
                font.features: { "tnum": 1 }
            }
        }
    }
}
