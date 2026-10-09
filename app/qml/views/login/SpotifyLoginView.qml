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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import QtQuick
import QtWebView

// Spotify login for Canvas loops. The helper watches the cookie store, so this
// page only has to look like a browser and get out of the way.
Item {
    id: root

    signal closeRequested

    anchors.fill: parent

    Rectangle {
        id: navBar
        width: parent.width
        height: 48
        color: "#16191e"
        border.color: "#252a33"

        Text {
            anchors.left: parent.left
            anchors.leftMargin: 16
            anchors.right: closeButton.left
            anchors.verticalCenter: parent.verticalCenter
            text: webView.title ? webView.title : qsTr("Log in to Spotify")
            color: "#9ca3af"
            font.family: "Inter"
            font.pixelSize: 13
            elide: Text.ElideRight
        }

        Rectangle {
            id: closeButton
            anchors.right: parent.right
            anchors.rightMargin: 12
            anchors.verticalCenter: parent.verticalCenter
            width: 32
            height: 32
            radius: 6
            color: closeHover.containsMouse ? "#242932" : "transparent"

            Text {
                anchors.centerIn: parent
                text: "✕"
                color: "#9ca3af"
                font.pixelSize: 13
            }

            MouseArea {
                id: closeHover
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: root.closeRequested()
            }
        }

        Rectangle {
            anchors.bottom: parent.bottom
            anchors.left: parent.left
            width: parent.width * (webView.loadProgress / 100.0)
            height: 2
            color: "#1ed760"
            visible: webView.loading
        }
    }

    WebView {
        id: webView
        anchors.top: navBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        url: "https://accounts.spotify.com/en/login"
    }
}
