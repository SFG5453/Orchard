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

Item {
    id: root

    readonly property var feed: OrchardHome.sections
    // Lead album or playlist from each shelf, for the carousel up top. Library
    // shelves only fill in when the editorial ones run short.
    readonly property var features: {
        const editorial = [];
        const library = [];
        const sections = root.feed || [];
        for (let sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
            const title = sections[sectionIndex].title || "";
            const items = sections[sectionIndex].items || [];
            for (let itemIndex = 0; itemIndex < items.length; ++itemIndex) {
                const item = items[itemIndex];
                const type = String(item.type || "").toLowerCase();
                if ((type !== "album" && type !== "playlist") || !item.thumbnail || item.browseId === "VLLM")
                    continue;
                (/library/i.test(title) ? library : editorial).push({
                    "eyebrow": title,
                    "media": item
                });
                break;
            }
        }
        return editorial.concat(library).slice(0, 6);
    }

    function openMedia(media) {
        if (media.type === "album")
            root.albumRequested(media);
        else if (media.type === "artist")
            root.artistRequested(media);
        else if (media.type === "playlist")
            root.playlistRequested(media);
    }

    signal unsupportedRequested(var media)
    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)

    DetailListView {
        id: scroll

        anchors.fill: parent
        clip: true
        spacing: 36
        boundsBehavior: Flickable.StopAtBounds
        model: root.feed
        cacheBuffer: 400
        onModelChanged: Qt.callLater(() => {
            return scroll.positionViewAtBeginning();
        })
        ScrollBar.vertical.width: 5

        populate: Transition {
            NumberAnimation {
                property: "opacity"
                from: 0
                to: 1
                duration: 250
                easing.type: Easing.OutCubic
            }

            NumberAnimation {
                property: "scale"
                from: 0.95
                to: 1
                duration: 250
                easing.type: Easing.OutCubic
            }

        }

        add: Transition {
            NumberAnimation {
                property: "opacity"
                from: 0
                to: 1
                duration: 250
                easing.type: Easing.OutCubic
            }

            NumberAnimation {
                property: "scale"
                from: 0.95
                to: 1
                duration: 250
                easing.type: Easing.OutCubic
            }

        }

        header: Item {
            width: scroll.width
            height: homeTitle.height + (carousel.visible ? 22 + carousel.height : 0) + 36

            Text {
                id: homeTitle

                text: qsTr("Home")
                color: "#f2eee7"
                font.family: "Inter"
                font.pixelSize: 32
                font.weight: Font.Bold
                font.letterSpacing: -0.6
            }

            FeatureCarousel {
                id: carousel

                anchors.top: homeTitle.bottom
                anchors.topMargin: 22
                width: parent.width
                height: implicitHeight
                // A carousel of one is just a large, lonely card.
                visible: root.features.length >= 2
                features: root.features
                onMediaRequested: function(media) {
                    root.openMedia(media);
                }
                onAlbumRequested: function(album) {
                    root.albumRequested(album);
                }
                onArtistRequested: function(artist) {
                    root.artistRequested(artist);
                }
                onPlaylistRequested: function(playlist) {
                    root.playlistRequested(playlist);
                }
                onVerticalScrollRequested: function(delta, smooth) {
                    scroll.scrollBy(delta, smooth);
                }
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
                const items = shelf.modelData.items || [];
                const tracks = [];
                // This shelf gets the aux cable, not the entire home page.
                for (let itemIndex = 0; itemIndex < items.length; ++itemIndex) tracks.push(items[itemIndex])
                const index = tracks.findIndex((track) => {
                    return track.id === song.id;
                });
                if (index >= 0)
                    OrchardPlayback.playCollection(tracks, index);
                else
                    OrchardPlayback.playSong(song);
            }
            onUnsupportedRequested: function(media) {
                root.unsupportedRequested(media);
            }
            onAlbumRequested: function(album) {
                root.albumRequested(album);
            }
            onArtistRequested: function(artist) {
                root.artistRequested(artist);
            }
            onPlaylistRequested: function(playlist) {
                root.playlistRequested(playlist);
            }
            onMediaRequested: function(media) {
                root.openMedia(media);
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
        width: Math.min(parent.width - 48, 400)
        spacing: 16
        visible: root.feed.length === 0

        BusyIndicator {
            anchors.horizontalCenter: parent.horizontalCenter
            running: OrchardHome.loading
            visible: running
        }

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            text: OrchardHome.loading ? qsTr("Loading your music…") : OrchardHome.errorMessage || qsTr("Your music home is empty. Refresh to try again.")
            color: "#a4adb1"
            font.family: "Inter"
            font.pixelSize: 13
        }

        Button {
            anchors.horizontalCenter: parent.horizontalCenter
            text: qsTr("Refresh")
            visible: !OrchardHome.loading
            onClicked: OrchardHome.refresh()
        }

    }

}
