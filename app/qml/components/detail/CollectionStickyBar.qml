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
import QtQuick.Effects
import ".."
import "../home"

// Compact title bar that slides in once the hero scrolls away.
Item {
    id: root

    property bool shown: false
    property string title
    property string artwork
    property real artworkRadius: 7
    property bool playing: false
    property bool actionsEnabled: true
    property color accentColor: "#7fbe90"
    property color accentInkColor: "#09100b"
    property color inkColor: "#080c0a"

    signal playClicked
    signal backToTopClicked

    height: 60
    visible: opacity > 0
    opacity: shown ? 1 : 0
    Behavior on opacity { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }

    Rectangle {
        id: bar
        width: parent.width
        height: parent.height
        y: root.shown ? 0 : -10
        Behavior on y { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
        radius: 18
        color: Qt.rgba(root.inkColor.r, root.inkColor.g, root.inkColor.b, 0.9)
        border.color: "#16ffffff"
        layer.enabled: GraphicsInfo.api !== GraphicsInfo.Software
        layer.effect: MultiEffect {
            shadowEnabled: true
            shadowBlur: 0.7
            shadowOpacity: 0.45
            shadowVerticalOffset: 8
        }

        TapHandler { onTapped: root.backToTopClicked() }
        HoverHandler { cursorShape: Qt.PointingHandCursor }

        Button {
            id: play
            x: 10
            width: 40
            height: 40
            anchors.verticalCenter: parent.verticalCenter
            enabled: root.actionsEnabled
            Accessible.name: root.playing ? qsTr("Pause") : qsTr("Play")
            onClicked: root.playClicked()
            scale: down ? 0.92 : hovered ? 1.06 : 1
            Behavior on scale { NumberAnimation { duration: 120 } }
            background: Rectangle { radius: width / 2; color: root.accentColor }
            contentItem: Item {
                LucideIcon {
                    anchors.centerIn: parent
                    anchors.horizontalCenterOffset: root.playing ? 0 : 1
                    width: 18
                    height: 18
                    name: root.playing ? "pause" : "play"
                    color: root.accentInkColor
                }
            }
        }
        RoundedArtwork {
            id: thumb
            x: play.x + play.width + 12
            width: 36
            height: 36
            radius: root.artworkRadius
            anchors.verticalCenter: parent.verticalCenter
            source: root.artwork
        }
        Text {
            x: thumb.x + thumb.width + 12
            width: parent.width - x - 16
            anchors.verticalCenter: parent.verticalCenter
            text: root.title
            color: "#f4f3ee"
            font.family: "Inter"
            font.pixelSize: 16
            font.weight: Font.Bold
            elide: Text.ElideRight
        }
    }
}
