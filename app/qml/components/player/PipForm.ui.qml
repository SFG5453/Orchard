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

// Small music video player: the picture with a few controls over it.
Rectangle {
    id: root

    property bool chromeShown: true
    property bool playing: false
    property bool busy: false
    property string message: ""
    property string backGlyph: "app-window"
    property string backLabel: qsTr("Back to app")
    property string closeLabel: qsTr("Close picture in picture")
    property bool popOutShown: false

    property alias picture: picture
    property alias backButton: backButton
    property alias playButton: playButton
    property alias closeButton: closeButton
    property alias popOutButton: popOutButton

    width: 384
    height: 216
    color: "#000000"

    TheaterPicture {
        id: picture

        anchors.fill: parent
        visible: root.message.length === 0
    }

    BusyIndicator {
        anchors.centerIn: parent
        running: root.busy
    }

    Label {
        anchors.centerIn: parent
        width: parent.width - 32
        visible: root.message.length > 0
        text: root.message
        color: "#f7f5f0"
        font.pixelSize: 13
        wrapMode: Text.Wrap
        horizontalAlignment: Text.AlignHCenter
    }

    Item {
        id: chrome

        anchors.fill: parent
        opacity: root.chromeShown ? 1 : 0
        visible: opacity > 0.01

        Behavior on opacity { NumberAnimation { duration: 200 } }

        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: 64
            gradient: Gradient {
                GradientStop { position: 0; color: "#99000000" }
                GradientStop { position: 1; color: "#00000000" }
            }
        }

        TheaterButton {
            id: backButton

            anchors.left: parent.left
            anchors.top: parent.top
            anchors.margins: 8
            size: 36
            iconSize: 18
            glyph: root.backGlyph
            label: root.backLabel
        }

        TheaterButton {
            id: closeButton

            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 8
            size: 36
            iconSize: 18
            glyph: "x"
            label: root.closeLabel
        }

        TheaterButton {
            id: popOutButton

            anchors.right: closeButton.left
            anchors.top: parent.top
            anchors.topMargin: 8
            anchors.rightMargin: 4
            visible: root.popOutShown
            size: 36
            iconSize: 18
            glyph: "picture-in-picture-2"
            label: qsTr("Picture in picture")
        }

        TheaterButton {
            id: playButton

            anchors.centerIn: parent
            size: 52
            iconSize: 24
            fill: "#66000000"
            glyph: root.playing ? "pause" : "play"
            label: root.playing ? qsTr("Pause") : qsTr("Play")
        }
    }
}
