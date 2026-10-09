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

// SponsorBlock skip offer over the picture, shown while the playhead is in a marked span.
Button {
    id: root

    readonly property var segment: OrchardPlayback.nonMusicSegment
    readonly property bool offered: OrchardPlayback.nonMusicSkipMode === "button"
                                    && segment !== undefined && segment.endTime !== undefined

    function categoryLabel(category) {
        switch (category) {
        case "sponsor": return qsTr("Skip sponsor");
        case "selfpromo": return qsTr("Skip self-promotion");
        case "interaction": return qsTr("Skip reminder");
        default: return qsTr("Skip non-music");
        }
    }

    visible: opacity > 0.01
    opacity: offered ? 1 : 0
    implicitHeight: 44
    implicitWidth: label.implicitWidth + 64
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    onClicked: OrchardPlayback.skipNonMusic()
    Accessible.name: label.text

    Behavior on opacity { NumberAnimation { duration: 140 } }

    background: Rectangle {
        radius: 8
        color: root.hovered ? "#e6202226" : "#cc0b0c0e"
        border.color: root.activeFocus ? "#f0eee7" : "#33ffffff"

        Behavior on color { ColorAnimation { duration: 90 } }
    }

    contentItem: Row {
        spacing: 10
        leftPadding: 18

        Text {
            id: label

            anchors.verticalCenter: parent.verticalCenter
            text: root.offered ? root.categoryLabel(root.segment.category || "") : ""
            color: "#f7f5f0"
            font.pixelSize: 14
            font.weight: Font.DemiBold
        }

        LucideIcon {
            anchors.verticalCenter: parent.verticalCenter
            name: "skip-forward"
            color: "#f7f5f0"
            width: 16
            height: 16
        }
    }
}
