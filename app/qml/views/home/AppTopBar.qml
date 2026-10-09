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
import "../../components"
import "../../components/home"

Item {
    id: root

    property alias query: search.text

    signal searchChanged(string query)
    signal settingsRequested
    signal docsRequested
    signal supportRequested

    function focusSearch() {
        search.forceActiveFocus();
    }

    function clearSearch() {
        search.clear();
    }

    // Canopy: search is a glass circle that grows into a field while focused or filled.
    readonly property bool canopy: OrchardAppearance.layoutStyle === "canopy"
    readonly property bool searchOpen: !canopy || search.activeFocus || search.text.length > 0
    // Left edge of the buttons on the right, which the search field must clear.
    readonly property real buttonsLeft: offlineStatus.visible ? offlineStatus.x : supportButton.x
    // The player and search share the header's content coordinates.
    readonly property real canopyPlayerWidth: Math.max(0, searchBox.x - 12)

    Rectangle {
        id: searchBox

        // Centered; slides left when the account button would overlap.
        x: root.canopy ? root.buttonsLeft - 12 - width
                  : Math.max(0, Math.min((root.width - width) / 2, root.buttonsLeft - 28 - width))
        anchors.verticalCenter: parent.verticalCenter
        // Reserve room for the player's transport and side controls while search is open.
        width: root.canopy ? (root.searchOpen ? Math.min(300, Math.max(38, root.buttonsLeft - 24 - 360)) : 38)
                      : Math.max(120, Math.min(660, root.buttonsLeft - 28))
        height: root.canopy ? 38 : 42
        radius: height / 2
        clip: true
        color: search.activeFocus ? "#1effffff" : "#15ffffff"
        border.color: search.activeFocus ? "#c9d8cd" : "#70ffffff"

        Behavior on width {
            enabled: root.canopy
            NumberAnimation { duration: Motion.slow; easing.type: Motion.enter }
        }

        Behavior on color {
            ColorAnimation { duration: Motion.normal }
        }

        Behavior on border.color {
            ColorAnimation { duration: Motion.normal }
        }

        LucideIcon {
            x: root.canopy ? 11 : 14
            anchors.verticalCenter: parent.verticalCenter
            width: 16
            height: 16
            name: "search"
            color: "#bec4c7"
        }

        TextField {
            id: search

            anchors.fill: parent
            leftPadding: root.canopy ? 38 : 42
            rightPadding: 38
            opacity: root.searchOpen ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: Motion.normal } }
            placeholderText: OrchardNetwork.offline ? qsTr("Search your downloads…") : qsTr("Search YouTube Music…")
            placeholderTextColor: "#929ba1"
            color: "#f1eee7"
            font.family: "Inter"
            font.pixelSize: 12
            onTextChanged: root.searchChanged(text)

            background: Item {}
        }
        // Opens the collapsed circle; the field takes over once it has focus.
        MouseArea {
            anchors.fill: parent
            enabled: !root.searchOpen
            cursorShape: Qt.PointingHandCursor
            onClicked: root.focusSearch()
        }

        Button {
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.rightMargin: 6
            width: 30; height: 30
            // Pops in like it heard its name, then gets out of the way.
            opacity: search.text.length > 0 ? 1 : 0
            scale: search.text.length > 0 ? 1 : 0.6
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: Motion.fast; easing.type: Motion.enter } }
            Behavior on scale { NumberAnimation { duration: Motion.normal; easing.type: Easing.OutBack } }
            Accessible.name: qsTr("Clear search")
            onClicked: root.clearSearch()
            background: Rectangle {
                radius: 15
                color: parent.hovered || parent.activeFocus ? "#20ffffff" : "#00ffffff"
                Behavior on color { ColorAnimation { duration: Motion.fast } }
            }
            contentItem: Text { text: "×"; color: "#d6d9d3"; font.pixelSize: 23; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
        }
    }

    OfflineStatusButton {
        id: offlineStatus

        anchors.right: supportButton.left
        anchors.rightMargin: 8
        anchors.verticalCenter: parent.verticalCenter
    }

    Button {
        id: supportButton

        anchors.right: docsButton.left
        anchors.rightMargin: 4
        anchors.verticalCenter: parent.verticalCenter
        width: 38
        height: 38
        onClicked: root.supportRequested()
        Accessible.name: OrchardSupport.unread > 0 ? qsTr("Report a bug, %1 updates").arg(OrchardSupport.unread) : qsTr("Report a bug")
        ToolTip.visible: hovered
        ToolTip.text: OrchardSupport.unread > 0 ? qsTr("Bug reports have updates") : qsTr("Report a bug")

        background: Rectangle {
            radius: 19
            color: supportButton.hovered ? "#12ffffff" : "#00ffffff"

            Behavior on color {
                ColorAnimation { duration: Motion.fast }
            }
        }

        contentItem: LucideIcon {
            name: "bug"
            color: supportButton.hovered ? "#f1eee7" : "#bec4c7"
            Behavior on color { ColorAnimation { duration: Motion.fast } }
        }

        // News on a report. The bug button finally has something to say.
        Rectangle {
            x: parent.width - width - 7
            y: 7
            width: 8
            height: 8
            radius: 4
            color: "#8cc5a5"
            border.color: "#0d0f12"
            visible: OrchardSupport.unread > 0
        }
    }

    Button {
        id: docsButton

        anchors.right: account.left
        anchors.rightMargin: 4
        anchors.verticalCenter: parent.verticalCenter
        width: 38
        height: 38
        onClicked: root.docsRequested()
        Accessible.name: qsTr("Docs")
        ToolTip.visible: hovered
        ToolTip.text: qsTr("Docs")

        background: Rectangle {
            radius: 19
            color: docsButton.hovered ? "#12ffffff" : "#00ffffff"

            Behavior on color {
                ColorAnimation { duration: Motion.fast }
            }
        }

        contentItem: LucideIcon {
            name: "book-open"
            color: docsButton.hovered ? "#f1eee7" : "#bec4c7"
            Behavior on color { ColorAnimation { duration: Motion.fast } }
        }
    }

    Button {
        id: account

        anchors.right: parent.right
        anchors.verticalCenter: parent.verticalCenter
        width: root.width > 730 ? 176 : 38
        height: 38
        onClicked: root.settingsRequested()
        Accessible.name: qsTr("Account and settings")
        ToolTip.visible: hovered
        ToolTip.text: qsTr("Account and settings")

        background: Rectangle {
            radius: 19
            color: account.hovered ? "#12ffffff" : "#00ffffff"

            Behavior on color {
                ColorAnimation { duration: Motion.fast }
            }
        }

        contentItem: Row {
            spacing: 10

            RoundedArtwork {
                width: 32
                height: 32
                radius: 16
                source: OrchardAuth.userAvatar || ""
            }

            Column {
                width: account.width - 48
                anchors.verticalCenter: parent.verticalCenter
                visible: account.width > 38

                Text {
                    width: parent.width
                    text: OrchardAuth.userName || qsTr("Your account")
                    color: "#dce0df"
                    font.family: "Inter"
                    font.pixelSize: 12
                    elide: Text.ElideRight
                }

                Text {
                    width: parent.width
                    visible: OrchardAuth.userHandle !== ""
                    text: OrchardAuth.userHandle
                    color: "#929ba1"
                    font.family: "Inter"
                    font.pixelSize: 11
                    elide: Text.ElideRight
                }
            }
        }
    }
}
