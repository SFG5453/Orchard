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
import QtQuick.Layouts

// One "##" section of a page: a titled row in the page panel, split from its neighbours by a hairline.
Item {
    id: root
    property string title
    // Position among the rows; later rows start a little later.
    property int order: 0
    // 0 at rest, 1 while the row is pointed out.
    property real glow: 0
    default property alias content: body.data

    Layout.fillWidth: true

    // Marks the row a search answer led to.
    function flash() {
        glowAnimation.restart();
    }

    implicitHeight: body.implicitHeight + 44
    opacity: 0
    transform: Translate { id: slide; y: 18 }

    Component.onCompleted: entrance.start()

    SequentialAnimation {
        id: entrance
        PauseAnimation { duration: Math.min(root.order, 6) * 45 }
        ParallelAnimation {
            NumberAnimation { target: root; property: "opacity"; to: 1; duration: 260; easing.type: Easing.OutCubic }
            NumberAnimation { target: slide; property: "y"; to: 0; duration: 340; easing.type: Easing.OutCubic }
        }
    }

    SequentialAnimation {
        id: glowAnimation
        NumberAnimation { target: root; property: "glow"; to: 1; duration: 180; easing.type: Easing.OutCubic }
        PauseAnimation { duration: 1100 }
        NumberAnimation { target: root; property: "glow"; to: 0; duration: 700; easing.type: Easing.InOutQuad }
    }

    // The first row of a panel gets no divider.
    Rectangle {
        visible: root.y > 0
        width: parent.width
        height: 1
        color: "#12ffffff"
    }

    Rectangle {
        anchors.fill: parent
        anchors.margins: 6
        radius: 12
        color: Qt.rgba(0.549, 0.773, 0.647, 0.1 * root.glow)
        border.color: Qt.rgba(0.549, 0.773, 0.647, 0.9 * root.glow)
    }

    ColumnLayout {
        id: body
        x: 20
        y: 22
        width: parent.width - 40
        spacing: 12

        Text {
            Layout.fillWidth: true
            text: root.title
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 16
            font.weight: Font.DemiBold
            wrapMode: Text.WordWrap
        }
    }
}
