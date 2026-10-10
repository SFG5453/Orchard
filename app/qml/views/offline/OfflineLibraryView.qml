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
import "../library"
import QtQuick
import QtQuick.Controls

// Library while offline: downloaded and local songs, plus saved playlists.
Item {
    id: root

    property string filter: "all"
    // Applies to the songs table; empty keeps the newest-first order.
    property string sortKey: ""
    property bool sortDescending: false
    readonly property string query: header.query.trim().toLowerCase()
    readonly property var categories: [
        { key: "songs", title: qsTr("Songs"), empty: qsTr("Songs you download or add from this computer appear here.") },
        { key: "playlists", title: qsTr("Playlists"), empty: qsTr("Downloaded playlists and playlists made from local files appear here.") }
    ]
    readonly property var loadedSections: categories.map(category => ({
        key: category.key, title: category.title, empty: category.empty,
        items: category.key === "songs" ? OrchardOffline.songs : OrchardOffline.playlists
    }))
    readonly property var sections: loadedSections.map(section => visibleSection(section))
    readonly property var selectedSection: sections.find(section => section.key === filter) || ({ items: [] })
    readonly property var counts: ({ songs: loadedSections[0].items.length, playlists: loadedSections[1].items.length })

    signal playlistRequested(var playlist)
    signal albumRequested(var album)
    signal artistRequested(var artist)

    function matches(item) {
        const text = [item.title, item.artist, item.author, (item.artists || []).join(" "), item.album]
            .filter(Boolean).join(" ").toLowerCase();
        return query.split(/\s+/).every(token => text.includes(token));
    }

    function sortValue(item) {
        if (sortKey === "artist")
            return (item.artists || []).join(", ") || item.artist;
        if (sortKey === "duration")
            return Number(item.durationSeconds) || 0;
        return item[sortKey] || "";
    }

    function sortedTracks(items) {
        const direction = sortDescending ? -1 : 1;
        return items.slice().sort((a, b) => {
            const left = sortValue(a);
            const right = sortValue(b);
            return direction * (typeof left === "number" ? left - right : String(left).localeCompare(String(right)));
        });
    }

    // Filter first, then sort, so the queue plays what the table shows.
    function visibleSection(section) {
        let items = query ? section.items.filter(item => matches(item)) : section.items;
        if (section.key === "songs" && sortKey)
            items = sortedTracks(items);
        return { key: section.key, title: section.title, empty: section.empty, items: items, total: section.items.length };
    }

    function summaryText() {
        const parts = [];
        if (counts.songs > 0)
            parts.push(qsTr("%1 songs").arg(counts.songs.toLocaleString(Qt.locale(), "f", 0)));
        if (counts.playlists > 0)
            parts.push(qsTr("%1 playlists").arg(counts.playlists.toLocaleString(Qt.locale(), "f", 0)));
        return parts.join(" · ");
    }

    function sectionStatus(section) {
        if (query && section.total > 0)
            return qsTr("Nothing in %1 matches \"%2\".").arg(section.title.toLowerCase()).arg(header.query.trim());
        return section.empty || "";
    }

    function activate(media, section) {
        if (media.type === "playlist") {
            playlistRequested(media);
            return;
        }
        const tracks = (section.items || []).filter(item => item.id && !item.unplayable && item.type !== "playlist");
        const index = tracks.findIndex(track => track.id === media.id);
        if (index >= 0)
            OrchardPlayback.playCollection(tracks, index);
    }

    function resetScroll() {
        shelves.positionViewAtBeginning();
        grid.positionViewAtBeginning();
        songs.positionViewAtBeginning();
    }

    onQueryChanged: resetScroll()
    onFilterChanged: resetScroll()

    component Caption: Text {
        color: "#f2eee7"
        font.family: "Inter"
        font.pixelSize: 13
    }

    Column {
        id: headerColumn
        width: parent.width
        spacing: 18

        LibraryHeader {
            id: header
            width: parent.width
            categories: root.categories
            counts: root.counts
            filter: root.filter
            summary: root.summaryText()
            loading: OrchardNetwork.checking
            onFilterSelected: key => root.filter = key
            onRefreshRequested: OrchardNetwork.retry()
        }

        OfflineNotice {
            width: parent.width
            detail: qsTr("These are the songs and playlists saved on this computer. Use Refresh to try the connection again.")
        }
    }

    DetailListView {
        id: shelves
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: headerColumn.bottom
        anchors.topMargin: 24
        anchors.bottom: parent.bottom
        visible: root.filter === "all"
        spacing: 24
        cacheBuffer: 400
        model: root.query ? root.sections.filter(section => section.items.length > 0) : root.sections
        ScrollBar.vertical.width: 5

        delegate: Column {
            id: sectionColumn
            required property var modelData
            readonly property bool songList: modelData.key === "songs"
            readonly property var shelfSection: ({ key: modelData.key, title: modelData.title,
                                                    items: modelData.items.slice(0, songList ? 24 : 20) })
            width: shelves.width
            spacing: 12

            ShelfView {
                width: parent.width
                height: visible ? implicitHeight : 0
                visible: sectionColumn.modelData.items.length > 0
                section: sectionColumn.shelfSection
                presentation: sectionColumn.songList ? "songlist" : "playlist"
                songsEnabled: true
                seeAllEnabled: true
                onSeeAllRequested: root.filter = sectionColumn.modelData.key
                onSongRequested: song => root.activate(song, sectionColumn.modelData)
                onMediaRequested: media => root.activate(media, sectionColumn.modelData)
                onPlaylistRequested: playlist => root.playlistRequested(playlist)
                onVerticalScrollRequested: (delta, smooth) => shelves.scrollBy(delta, smooth)
            }

            Caption {
                width: parent.width
                visible: sectionColumn.modelData.items.length === 0
                text: sectionColumn.modelData.title
                font.pixelSize: 20
                font.weight: Font.DemiBold
                elide: Text.ElideRight
            }
            Caption {
                width: parent.width
                visible: sectionColumn.modelData.items.length === 0
                text: root.sectionStatus(sectionColumn.modelData)
                color: "#a4adb1"
                wrapMode: Text.Wrap
                bottomPadding: 12
            }
        }

        footer: Item { width: 1; height: 28 }

        Caption {
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 440)
            visible: shelves.count === 0 && root.query !== ""
            text: qsTr("Nothing downloaded matches \"%1\".").arg(header.query.trim())
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: "#a4adb1"
        }
    }

    LibrarySongList {
        id: songs
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: headerColumn.bottom
        anchors.topMargin: 24
        anchors.bottom: parent.bottom
        visible: root.filter === "songs"
        tracks: visible ? root.selectedSection.items : []
        sortKey: root.sortKey
        sortDescending: root.sortDescending
        emptyText: root.sectionStatus(root.selectedSection)
        onSortRequested: (key, descending) => {
            root.sortKey = key;
            root.sortDescending = descending;
        }
        onTrackRequested: track => root.activate(track, root.selectedSection)
        onAlbumRequested: album => root.albumRequested(album)
        onArtistRequested: artist => root.artistRequested(artist)
        onPlaylistRequested: playlist => root.playlistRequested(playlist)
    }

    // Virtualize a full category so large libraries don't create every card at once.
    GridView {
        id: grid
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: headerColumn.bottom
        anchors.topMargin: 24
        anchors.bottom: parent.bottom
        visible: root.filter === "playlists"
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        readonly property int cardWidth: 178
        cellWidth: width / Math.max(1, Math.floor(width / (cardWidth + 14)))
        cellHeight: 242 + 14
        model: visible ? root.selectedSection.items : []
        cacheBuffer: 300
        bottomMargin: shelves.playerInset
        ScrollBar.vertical: ScrollBar { width: 5; bottomPadding: shelves.playerInset }

        delegate: MediaCard {
            required property var modelData
            media: modelData
            width: Math.min(grid.cardWidth, grid.cellWidth - 14)
            presentation: "playlist"
            navigable: true
            onActivated: media => root.activate(media, root.selectedSection)
            onPlaylistRequested: playlist => root.playlistRequested(playlist)
        }

        Caption {
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 440)
            visible: grid.count === 0
            text: root.sectionStatus(root.selectedSection)
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: "#a4adb1"
        }

        footer: Item { width: 1; height: 28 }
    }
}
