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
    property alias query: sidebarSearch.query
    readonly property var navKeys: ["home", "library"]
    readonly property int navIndex: navKeys.indexOf(currentPage)
    // Labels fade with the width animation; clip keeps them inside while it runs.
    property real labelOpacity: compact ? 0 : 1
    signal pageRequested(string page)
    signal playlistRequested(var playlist)
    signal searchChanged(string query)
    signal spotlightRequested

    function focusSearch() { sidebarSearch.focusSearch(); }
    function clearSearch() { sidebarSearch.clearSearch(); }

    function playlistKey(playlist) {
        let key = String(playlist.playlistId || playlist.browseId || playlist.id || "");
        if (key.startsWith("offline-playlist:"))
            key = key.substring("offline-playlist:".length);
        if (key.startsWith("VL"))
            key = key.substring(2);
        return key;
    }

    function orderedPlaylists(items, order) {
        const result = Array.from(items);
        result.sort((a, b) => {
            const ai = order.indexOf(playlistKey(a));
            const bi = order.indexOf(playlistKey(b));
            return (ai < 0 ? order.length : ai) - (bi < 0 ? order.length : bi);
        });
        return result;
    }

    function movePlaylist(from, to) {
        const items = playlistList.model;
        if (from < 0 || to < 0 || from >= items.length || to >= items.length || from === to)
            return;
        const source = playlistKey(items[from]);
        const target = playlistKey(items[to]);
        if (!source || !target || source === target)
            return;
        const order = Array.from(OrchardHome.sidebarPlaylistOrder);
        for (const item of items) {
            const key = playlistKey(item);
            if (key && order.indexOf(key) < 0)
                order.push(key);
        }
        order.splice(order.indexOf(source), 1);
        const targetIndex = order.indexOf(target);
        order.splice(targetIndex + (from < to ? 1 : 0), 0, source);
        OrchardHome.sidebarPlaylistOrder = order;
    }

    clip: true

    Behavior on labelOpacity {
        NumberAnimation { duration: Motion.normal; easing.type: Motion.enter }
    }

    // One highlight glides between nav rows. Detail pages park it on the last row it left.
    Rectangle {
        id: navHighlight

        property int shownIndex: 0

        x: mainColumn.x
        y: mainColumn.y + sidebarSearch.y + sidebarSearch.height + mainColumn.spacing + shownIndex * (42 + mainColumn.spacing)
        width: mainColumn.width
        height: 42
        radius: 9
        color: "#18ffffff"
        opacity: root.navIndex >= 0 ? 1 : 0

        // Accent bar on the active entry, Windows style.
        Rectangle {
            x: 0
            anchors.verticalCenter: parent.verticalCenter
            width: 3
            height: 18
            radius: 1.5
            color: OrchardAppearance.accent
        }

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
            width: parent.width
            height: 66
            spacing: 9
            leftPadding: root.compact ? Math.max(0, (width - 32) / 2) : 0

            Image {
                anchors.verticalCenter: parent.verticalCenter
                width: 32
                height: 32
                source: "../../assets/orchard-logo.png"
                sourceSize: Qt.size(64, 64)
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

        SidebarSearch {
            id: sidebarSearch

            width: root.compact ? 34 : parent.width
            x: (parent.width - width) / 2
            compact: root.compact
            onSearchChanged: function(query) { root.searchChanged(query); }
            onSpotlightRequested: root.spotlightRequested()
        }

        Repeater {
            model: [{
                "key": "home",
                "title": qsTr("Home"),
                "icon": "home"
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
                    border.color: nav.activeFocus ? OrchardAppearance.accent : "transparent"

                    Behavior on color {
                        ColorAnimation { duration: Motion.fast }
                    }
                }

                contentItem: Row {
                    spacing: 13
                    leftPadding: root.compact ? Math.max(0, (width - 19) / 2) : 0

                    LucideIcon {
                        anchors.verticalCenter: parent.verticalCenter
                        width: 19
                        height: 19
                        name: nav.modelData.icon
                        color: root.currentPage === nav.modelData.key ? OrchardAppearance.accent : "#c1c7ca"
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

        // Opens the catalog-service-or-local chooser. The dialog destroys itself on close.
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
                border.color: newPlaylistButton.activeFocus ? OrchardAppearance.accent : "#18ffffff"

                Behavior on color {
                    ColorAnimation { duration: Motion.fast }
                }
            }

            contentItem: Row {
                spacing: 10
                leftPadding: root.compact ? Math.max(0, (width - 16) / 2) : 4

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
            objectName: "sidebarPlaylists"
            property int dragFrom: -1
            property int dropIndex: -1
            cacheBuffer: dragFrom >= 0 ? contentHeight : 320

            width: parent.width
            // Playlists own everything below the nav; the player pill floats clear of the sidebar.
            height: Math.max(0, mainColumn.height - y)
            clip: true
            spacing: 4
            model: root.orderedPlaylists(OrchardNetwork.offline ? OrchardOffline.playlists
                : (OrchardLocal.playlists || []).concat((OrchardHome.playlists || []).filter((p) => {
                    return p.title !== "New playlist";
                })), OrchardHome.sidebarPlaylistOrder)

            ScrollBar.vertical: ScrollBar {
                width: 3
            }

            delegate: Button {
                id: shortcut

                required property var modelData
                required property int index
                readonly property bool isLocal: modelData.source === "local" || modelData.source === "download"
                property real pointerY: 0

                function updateDrop() {
                    const y = playlistList.contentY + pointerY;
                    playlistList.dropIndex = Math.max(0, Math.min(playlistList.count - 1,
                        Math.floor((y - playlistList.originY) / (height + playlistList.spacing))));
                }

                width: ListView.view.width
                height: 40
                // The playlist buttons were dressed for the party but forgot to RSVP.
                onClicked: root.playlistRequested(shortcut.modelData)
                ToolTip.visible: root.visible && root.enabled && hovered
                ToolTip.text: modelData.title || ""

                MouseArea {
                    id: dragArea
                    anchors.fill: parent
                    preventStealing: true
                    property real pressY: 0
                    property bool dragging: false
                    onPressed: function(mouse) {
                        pressY = mapToItem(playlistList, mouse.x, mouse.y).y;
                        shortcut.pointerY = pressY;
                        dragging = false;
                    }
                    onPositionChanged: function(mouse) {
                        if (!pressed) return;
                        shortcut.pointerY = mapToItem(playlistList, mouse.x, mouse.y).y;
                        if (!dragging && Math.abs(shortcut.pointerY - pressY) >= Qt.styleHints.startDragDistance) {
                            dragging = true;
                            playlistList.dragFrom = shortcut.index;
                        }
                        if (dragging) shortcut.updateDrop();
                    }
                    onReleased: {
                        if (dragging) {
                            const from = playlistList.dragFrom;
                            const to = playlistList.dropIndex;
                            playlistList.dragFrom = -1;
                            playlistList.dropIndex = -1;
                            root.movePlaylist(from, to);
                        } else {
                            root.playlistRequested(shortcut.modelData);
                        }
                        dragging = false;
                    }
                    onCanceled: {
                        playlistList.dragFrom = -1;
                        playlistList.dropIndex = -1;
                        dragging = false;
                    }
                }

                Timer {
                    interval: 30
                    repeat: true
                    running: dragArea.dragging
                    onTriggered: {
                        const y = shortcut.pointerY;
                        const bottom = Math.max(playlistList.originY, playlistList.originY + playlistList.contentHeight - playlistList.height);
                        if (y < 35)
                            playlistList.contentY = Math.max(playlistList.originY, playlistList.contentY - 10);
                        else if (y > playlistList.height - 35)
                            playlistList.contentY = Math.min(bottom, playlistList.contentY + 10);
                        shortcut.updateDrop();
                    }
                }

                Rectangle {
                    visible: playlistList.dropIndex === shortcut.index && playlistList.dragFrom !== shortcut.index
                    y: playlistList.dragFrom > shortcut.index ? 0 : parent.height - height
                    width: parent.width
                    height: 2
                    radius: 1
                    color: OrchardAppearance.accent
                    z: 2
                }

                background: Rectangle {
                    radius: 8
                    color: shortcut.hovered ? "#12ffffff" : "#00ffffff"
                    border.color: shortcut.activeFocus ? OrchardAppearance.accent : "transparent"

                    Behavior on color {
                        ColorAnimation { duration: Motion.fast }
                    }
                }

                contentItem: Row {
                    spacing: 10
                    leftPadding: root.compact ? Math.max(0, (width - 28) / 2) : 0

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

            FlickableWheel {
                view: playlistList
            }
        }

    }

}
