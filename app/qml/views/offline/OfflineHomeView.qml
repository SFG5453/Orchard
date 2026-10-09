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

pragma ComponentBehavior: Bound

import "../../components"
import "../../components/home"
import QtQuick
import QtQuick.Controls

// Home while offline: shelves of what plays without a connection.
Item {
    id: root

    readonly property var feed: OrchardOffline.homeSections

    signal playlistRequested(var playlist)

    function playFrom(items, song) {
        const index = items.findIndex(track => track.id === song.id);
        if (index >= 0)
            OrchardPlayback.playCollection(items, index);
        else
            OrchardPlayback.playSong(song);
    }

    DetailListView {
        id: scroll

        anchors.fill: parent
        clip: true
        spacing: 36
        boundsBehavior: Flickable.StopAtBounds
        model: root.feed
        cacheBuffer: 400
        ScrollBar.vertical.width: 5

        header: Item {
            width: scroll.width
            height: homeTitle.height + 18 + notice.height + 36

            Text {
                id: homeTitle

                text: qsTr("Home")
                color: "#f2eee7"
                font.family: "Inter"
                font.pixelSize: 32
                font.weight: Font.Bold
                font.letterSpacing: -0.6
            }

            OfflineNotice {
                id: notice

                anchors.top: homeTitle.bottom
                anchors.topMargin: 18
                width: parent.width
            }
        }

        delegate: ShelfView {
            id: shelf

            required property var modelData
            required property int index

            width: scroll.width
            section: shelf.modelData
            sectionIndex: shelf.index
            songsEnabled: true
            homeStyle: true
            onSongRequested: function(song) {
                root.playFrom(shelf.modelData.items || [], song);
            }
            onMediaRequested: function(media) {
                if (media.type === "playlist")
                    root.playlistRequested(media);
            }
            onPlaylistRequested: function(playlist) {
                root.playlistRequested(playlist);
            }
            onVerticalScrollRequested: function(delta, smooth) {
                scroll.scrollBy(delta, smooth);
            }
        }

        footer: Item {
            width: 1
            height: 28
        }
    }

    Column {
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 420)
        spacing: 12
        visible: root.feed.length === 0

        LucideIcon {
            anchors.horizontalCenter: parent.horizontalCenter
            width: 34
            height: 34
            name: "download"
            color: "#a4adb1"
        }
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            text: qsTr("Nothing is saved for offline listening yet. While you are online, open a song's menu and choose Download, or download a whole playlist or album from its page.")
            color: "#a4adb1"
            font.family: "Inter"
            font.pixelSize: 13
        }
    }
}
