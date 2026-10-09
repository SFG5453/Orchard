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

// One Up next entry. Rows cascade in whenever the pane swaps to the queue.
ItemDelegate {
    id: row

    property var track: ({ title: "Midnight City", artist: "M83", duration: "4:03" })
    property int rowIndex: 0
    // Artist line; the logic layer joins the artists list when there is no single artist.
    property string artistText: track.artist || ""
    property real paneSwap: 1
    property color accentColor: "#f0eee7"
    property color primaryText: "#f7f5f0"
    property color mutedText: "#8d928a"
    property alias removeButton: removeButton
    readonly property real enter: Math.max(0, Math.min(1, paneSwap * 1.8 - Math.min(rowIndex, 8) * 0.1))

    width: 400
    height: 62
    hoverEnabled: true
    opacity: enter
    transform: Translate { x: 30 * (1 - row.enter) }
    Accessible.name: (track.title || "") + ", " + artistText

    background: Rectangle {
        radius: 14
        color: row.down ? "#24ffffff" : row.hovered ? "#16ffffff" : "transparent"
        border.color: row.activeFocus ? row.accentColor : "transparent"
        Behavior on color { ColorAnimation { duration: 140 } }
    }

    contentItem: RowLayout {
        spacing: 14

        RoundedArtwork {
            Layout.preferredWidth: 46
            Layout.preferredHeight: 46
            radius: 8
            source: row.track.thumbnail || ""
            scale: row.hovered ? 1.06 : 1
            Behavior on scale { NumberAnimation { duration: 240; easing.type: Easing.OutBack; easing.overshoot: 2 } }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            Text {
                Layout.fillWidth: true
                text: row.track.title || ""
                color: row.primaryText
                font.family: "Inter"
                font.pixelSize: 15
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }

            Text {
                Layout.fillWidth: true
                text: row.artistText
                color: row.mutedText
                font.family: "Inter"
                font.pixelSize: 12
                elide: Text.ElideRight
            }
        }

        Text {
            text: row.track.duration || ""
            color: row.mutedText
            font.family: "Inter"
            font.pixelSize: 12
        }

        FullscreenIconButton {
            id: removeButton
            objectName: "removeFullscreenQueuedSong"
            Layout.preferredWidth: 32
            Layout.preferredHeight: 32
            glyph: "x"
            glyphSize: 16
            accentColor: row.accentColor
            primaryText: row.primaryText
            secondaryText: row.mutedText
            ToolTip.text: qsTr("Remove %1 from queue").arg(row.track.title || "")
        }
    }
}
