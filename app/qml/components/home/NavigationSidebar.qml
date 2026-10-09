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

Item {
    id: root

    property string currentPage: "home"
    property bool compact: false
    readonly property var navKeys: ["home", "search", "library"]
    readonly property int navIndex: navKeys.indexOf(currentPage)
    // Labels fade with the width animation; clip keeps them inside while it runs.
    property real labelOpacity: compact ? 0 : 1
    signal pageRequested(string page)
    signal playlistRequested(var playlist)

    clip: true

    Behavior on labelOpacity {
        NumberAnimation { duration: Motion.normal; easing.type: Motion.enter }
    }

    // One highlight glides between nav rows. Detail pages park it on the last row it left.
    Rectangle {
        id: navHighlight

        property int shownIndex: 0

        x: mainColumn.x
        y: mainColumn.y + 66 + mainColumn.spacing + shownIndex * (42 + mainColumn.spacing)
        width: mainColumn.width
        height: 42
        radius: 9
        color: "#18ffffff"
        opacity: root.navIndex >= 0 ? 1 : 0

        Binding on shownIndex {
            value: root.navIndex
            when: root.navIndex >= 0
            restoreMode: Binding.RestoreNone
        }

        Behavior on y {
            NumberAnimation { duration: Motion.slow; easing.type: Easing.OutBack; easing.overshoot: 0.8 }
        }

        Behavior on opacity {
            NumberAnimation { duration: Motion.normal }
        }
    }

    Column {
        id: mainColumn

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: root.compact ? 12 : 18
        spacing: 8

        Row {
            height: 66
            spacing: 9

            Image {
                anchors.verticalCenter: parent.verticalCenter
                width: 32
                height: 32
                source: "../../assets/orchard-logo.png"
                fillMode: Image.PreserveAspectFit
            }

            Text {
                anchors.verticalCenter: parent.verticalCenter
                opacity: root.labelOpacity
                visible: opacity > 0
                text: "Orchard"
                color: "#f1eee7"
                font.family: "Inter"
                font.pixelSize: 25
            }

        }

        Repeater {
            model: [{
                "key": "home",
                "title": qsTr("Home"),
                "icon": "home"
            }, {
                "key": "search",
                "title": qsTr("Search"),
                "icon": "search"
            }, {
                "key": "library",
                "title": qsTr("Your library"),
                "icon": "library-big"
            }]

            Button {
                id: nav

                required property var modelData

                width: parent.width
                height: 42
                onClicked: root.pageRequested(modelData.key)
                ToolTip.visible: root.visible && root.enabled && hovered
                ToolTip.text: modelData.title

                // The active fill comes from navHighlight; this only handles hover and focus.
                background: Rectangle {
                    radius: 9
                    color: root.currentPage !== nav.modelData.key && nav.hovered ? "#0cffffff" : "#00ffffff"
                    border.color: nav.activeFocus ? "#99c3ac" : "transparent"

                    Behavior on color {
                        ColorAnimation { duration: Motion.fast }
                    }
                }

                contentItem: Row {
                    spacing: 13

                    LucideIcon {
                        anchors.verticalCenter: parent.verticalCenter
                        width: 19
                        height: 19
                        name: nav.modelData.icon
                        color: "#c1c7ca"
                    }

                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        opacity: root.labelOpacity
                        visible: opacity > 0
                        text: nav.modelData.title
                        color: root.currentPage === nav.modelData.key ? "#f5f3ee" : "#a6adb2"

                        Behavior on color {
                            ColorAnimation { duration: Motion.normal }
                        }
                        font.family: "Inter"
                        font.pixelSize: 12
                    }

                }

            }

        }

        Text {
            topPadding: 26
            bottomPadding: 10
            visible: !root.compact
            text: qsTr("PLAYLISTS")
            font.family: "Inter"
            font.pixelSize: 10
            font.letterSpacing: 1.8
            color: "#919b9f"
        }

        // Opens the YouTube-or-local chooser. The dialog destroys itself on close.
        Button {
            id: newPlaylistButton

            width: parent.width
            height: 38
            onClicked: newPlaylistDialog.createObject(Overlay.overlay).open()
            ToolTip.visible: root.visible && root.enabled && hovered
            ToolTip.text: qsTr("New playlist")
            Accessible.name: qsTr("New playlist")

            background: Rectangle {
                radius: 8
                color: newPlaylistButton.hovered ? "#1affffff" : "#0cffffff"
                border.color: newPlaylistButton.activeFocus ? "#99c3ac" : "#18ffffff"

                Behavior on color {
                    ColorAnimation { duration: Motion.fast }
                }
            }

            contentItem: Row {
                spacing: 10
                leftPadding: 4

                LucideIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    width: 16
                    height: 16
                    name: "plus"
                    color: "#c1c7ca"
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    opacity: root.labelOpacity
                    visible: opacity > 0
                    text: qsTr("New Playlist")
                    color: "#d3d8da"
                    font.family: "Inter"
                    font.pixelSize: 12
                    font.weight: Font.Medium
                }
            }
        }

        Component {
            id: newPlaylistDialog

            NewPlaylistDialog {}
        }

        ListView {
            id: playlistList

            width: parent.width
            // Playlists own everything below the nav; the player pill floats clear of the sidebar.
            height: Math.max(0, mainColumn.height - y)
            clip: true
            spacing: 4
            // Playlists from this computer lead; YouTube's follow. Offline, only what is saved here.
            model: OrchardNetwork.offline ? OrchardOffline.playlists
                : (OrchardLocal.playlists || []).concat((OrchardHome.playlists || []).filter((p) => {
                    return p.title !== "New playlist";
                }))

            ScrollBar.vertical: ScrollBar {
                width: 3
            }

            delegate: Button {
                id: shortcut

                required property var modelData
                readonly property bool isLocal: modelData.source === "local" || modelData.source === "download"

                width: ListView.view.width
                height: 40
                // The playlist buttons were dressed for the party but forgot to RSVP.
                onClicked: root.playlistRequested(shortcut.modelData)
                ToolTip.visible: root.visible && root.enabled && hovered
                ToolTip.text: modelData.title || ""

                background: Rectangle {
                    radius: 8
                    color: shortcut.hovered ? "#12ffffff" : "#00ffffff"
                    border.color: shortcut.activeFocus ? "#99c3ac" : "transparent"

                    Behavior on color {
                        ColorAnimation { duration: Motion.fast }
                    }
                }

                contentItem: Row {
                    spacing: 10

                    RoundedArtwork {
                        width: 28
                        height: 28
                        anchors.verticalCenter: parent.verticalCenter
                        radius: 5
                        source: shortcut.modelData.thumbnail || ""
                    }

                    Text {
                        width: shortcut.width - 58 - (shortcut.isLocal ? 20 : 0)
                        anchors.verticalCenter: parent.verticalCenter
                        opacity: root.labelOpacity
                        visible: opacity > 0
                        text: shortcut.modelData.title || ""
                        color: "#adb4b8"
                        font.family: "Inter"
                        font.pixelSize: 11
                        elide: Text.ElideRight
                    }

                    // A small drive marks playlists that live on this computer; an arrow marks saved ones.
                    LucideIcon {
                        anchors.verticalCenter: parent.verticalCenter
                        visible: shortcut.isLocal && root.labelOpacity > 0
                        opacity: root.labelOpacity
                        width: 12
                        height: 12
                        name: shortcut.modelData.source === "download" ? "circle-arrow-down" : "hard-drive"
                        color: "#7f898d"
                    }

                }

            }

            footer: Text {
                width: parent.width
                visible: !root.compact && playlistList.count === 0
                text: OrchardNetwork.offline ? qsTr("Downloaded playlists will appear here.")
                    : OrchardHome.playlistsLoading ? qsTr("Loading playlists…") : OrchardHome.playlistsError || qsTr("Your saved playlists will appear here.")
                wrapMode: Text.Wrap
                color: "#919b9f"
                font.pixelSize: 11
            }

        }

    }

}
