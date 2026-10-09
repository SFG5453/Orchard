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


import Orchard
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Upper theater bar: close, artist, Song/Video switch and the Up next toggle.
RowLayout {
    id: root

    property color accentColor: "#f0eee7"
    property bool upNextOpen: false
    signal upNextToggled()

    spacing: 12

    TheaterButton {
        glyph: "chevron-down"
        label: qsTr("Close video")
        outlined: true
        onClicked: OrchardMusicVideo.hide()
    }

    ColumnLayout {
        Layout.fillWidth: true
        Layout.leftMargin: 4
        spacing: 4

        Label {
            text: qsTr("MUSIC VIDEO")
            color: "#8d928a"
            font.pixelSize: 11
            font.weight: Font.DemiBold
            font.letterSpacing: 1.8
        }

        Label {
            Layout.fillWidth: true
            text: (OrchardPlayback.track || {}).artist || ""
            color: "#c3c6bf"
            font.pixelSize: 14
            font.weight: Font.Medium
            elide: Text.ElideRight
        }
    }

    // Song/Video switch; Song returns to the regular player.
    Rectangle {
        implicitWidth: modeRow.implicitWidth + 8
        implicitHeight: 44
        radius: 22
        color: "#0fffffff"
        border.color: "#14ffffff"

        Row {
            id: modeRow

            anchors.centerIn: parent
            spacing: 2

            Button {
                id: songMode

                text: qsTr("Song")
                height: 36
                onClicked: OrchardMusicVideo.hide()
                background: Rectangle { radius: 18; color: songMode.hovered ? "#14ffffff" : "transparent" }
                contentItem: Label { text: songMode.text; color: "#c3c6bf"; font.pixelSize: 13; leftPadding: 10; rightPadding: 10; verticalAlignment: Text.AlignVCenter }
            }

            Button {
                id: videoMode

                text: qsTr("Video")
                height: 36
                Accessible.checked: true
                background: Rectangle { radius: 18; color: root.accentColor }
                contentItem: Label { text: videoMode.text; color: "#0b0d0a"; font.pixelSize: 13; font.weight: Font.DemiBold; leftPadding: 10; rightPadding: 10; verticalAlignment: Text.AlignVCenter }
            }
        }
    }

    TheaterButton {
        glyph: "list-video"
        label: qsTr("Up next")
        active: root.upNextOpen
        onClicked: root.upNextToggled()
    }
}
