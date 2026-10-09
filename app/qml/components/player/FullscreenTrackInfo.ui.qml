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
import QtQuick.Effects
import QtQuick.Layouts

// Title and artist for one song in the fullscreen player. Two of them trade
// places through a blur while a mix runs.
ColumnLayout {
    id: info

    property var track: ({
        title: "Midnight City",
        artist: "M83",
        album: "Hurry Up, We're Dreaming"
    })
    // Artist line; the logic layer joins the artists list when there is no single artist.
    property string artistText: track.artist || ""
    // Set for the playing song only; the links open its menu targets.
    property bool interactive: false
    property bool compact: false
    property bool albumLinked: false
    property bool artistLinked: false
    property color primaryText: "#f7f5f0"
    property color accentColor: "#f0eee7"
    // 0 sharp and in place, 1 blurred away.
    property real away: 0
    property real drift: 0
    readonly property color dim: Qt.rgba(accentColor.r, accentColor.g, accentColor.b, 0.95)
    property alias titleArea: titleHover
    property alias artistArea: artistHover
    property alias albumArea: albumHover

    spacing: 3
    opacity: 1 - away
    visible: away < 1
    transform: Translate { y: info.drift * info.away }
    layer.enabled: away > 0 && away < 1
    layer.effect: MultiEffect {
        blurEnabled: true
        blur: info.away
        blurMax: 32
    }

    Row {
        Layout.fillWidth: true
        spacing: 8

        Text {
            width: Math.max(0, Math.min(implicitWidth, parent.width - (titleBadge.visible ? titleBadge.width + 8 : 0)
                            - (titleSlopBadge.visible ? titleSlopBadge.width + 8 : 0)))
            anchors.verticalCenter: parent.verticalCenter
            text: info.track.title || qsTr("Nothing playing")
            color: info.albumLinked && titleHover.containsMouse ? info.accentColor : info.primaryText
            font.family: "Inter"
            font.pixelSize: info.compact ? 22 : 26
            font.weight: Font.Bold
            font.underline: info.albumLinked && titleHover.containsMouse
            elide: Text.ElideRight
            maximumLineCount: 1

            MouseArea {
                id: titleHover
                anchors.fill: parent
                enabled: info.interactive
                hoverEnabled: true
                acceptedButtons: Qt.LeftButton | Qt.RightButton
                cursorShape: info.albumLinked ? Qt.PointingHandCursor : Qt.ArrowCursor
            }
        }

        ExplicitBadge {
            id: titleBadge
            anchors.verticalCenter: parent.verticalCenter
            visible: !!info.track.explicit
        }

        SlopBadge {
            id: titleSlopBadge
            anchors.verticalCenter: parent.verticalCenter
            trackId: info.track.id || ""
        }
    }

    Row {
        Layout.fillWidth: true
        spacing: 0

        Text {
            id: artistLabel
            width: Math.min(implicitWidth, Math.max(0, parent.width - (info.track.album ? 40 : 0)))
            text: info.artistText
            color: artistHover.containsMouse ? info.primaryText : info.dim
            font.family: "Inter"
            font.pixelSize: 16
            font.weight: Font.Medium
            elide: Text.ElideRight
            maximumLineCount: 1
            Behavior on color { ColorAnimation { duration: 140 } }

            MouseArea {
                id: artistHover
                anchors.fill: parent
                enabled: info.interactive
                hoverEnabled: true
                acceptedButtons: Qt.LeftButton | Qt.RightButton
                cursorShape: info.artistLinked ? Qt.PointingHandCursor : Qt.ArrowCursor
            }
        }

        Text {
            visible: !!info.track.album
            text: "  ·  "
            color: info.dim
            font.family: "Inter"
            font.pixelSize: 16
            font.weight: Font.Medium
        }

        Text {
            visible: !!info.track.album
            width: Math.min(implicitWidth, Math.max(0, parent.width - artistLabel.width - 40))
            text: info.track.album || ""
            color: albumHover.containsMouse ? info.primaryText : info.dim
            font.family: "Inter"
            font.pixelSize: 16
            font.weight: Font.Medium
            elide: Text.ElideRight
            maximumLineCount: 1
            Behavior on color { ColorAnimation { duration: 140 } }

            MouseArea {
                id: albumHover
                anchors.fill: parent
                enabled: info.interactive
                hoverEnabled: true
                acceptedButtons: Qt.LeftButton | Qt.RightButton
                cursorShape: info.albumLinked ? Qt.PointingHandCursor : Qt.ArrowCursor
            }
        }
    }
}
