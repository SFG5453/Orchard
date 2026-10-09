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
import QtQuick
import QtQuick.Controls
import Orchard

// Offers to hop over a talking intro, skit or applause that SponsorBlock volunteers marked.
// Only exists while the playhead is inside such a span and the setting is on "Button".
Button {
    id: root

    property color textColor: "#f5f3ee"
    property color fillColor: "#26ffffff"
    // Icon-only when there is no room for the words.
    property bool compact: false
    // Read through a plain property: compiled bindings and the track map don't get along.
    readonly property var segment: OrchardPlayback.nonMusicSegment
    readonly property bool offered: OrchardPlayback.nonMusicSkipMode === "button"
                                    && segment !== undefined && segment.endTime !== undefined

    visible: opacity > 0.01
    opacity: offered ? 1 : 0
    implicitHeight: 28
    implicitWidth: compact ? 28 : label.implicitWidth + icon.width + 28
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    onClicked: OrchardPlayback.skipNonMusic()
    Accessible.name: qsTr("Skip non-music")
    ToolTip.visible: hovered
    ToolTip.delay: 400
    ToolTip.text: qsTr("Skip the part that isn't music")

    // Fades in and out instead of popping; the span boundaries are not the user's fault.
    Behavior on opacity { NumberAnimation { duration: 140 } }

    background: Rectangle {
        radius: height / 2
        color: root.hovered ? Qt.lighter(root.fillColor, 1.4) : root.fillColor
        border.color: root.activeFocus ? root.textColor : "#18ffffff"
        Behavior on color { ColorAnimation { duration: 90 } }
    }

    contentItem: Row {
        spacing: 6
        anchors.centerIn: parent

        LucideIcon {
            id: icon
            anchors.verticalCenter: parent.verticalCenter
            name: "skip-forward"
            color: root.textColor
            width: 14
            height: 14
        }

        Text {
            id: label
            anchors.verticalCenter: parent.verticalCenter
            visible: !root.compact
            text: qsTr("Skip non-music")
            color: root.textColor
            font.family: "Inter"
            font.pixelSize: 11
            font.weight: Font.DemiBold
        }
    }
}
