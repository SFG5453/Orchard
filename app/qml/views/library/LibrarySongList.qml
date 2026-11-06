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
import "../../components/detail"
import QtQuick

// Saved songs as a sortable table.
Item {
    id: root

    property var tracks: []
    property string sortKey
    property bool sortDescending: false
    property string emptyText
    property color emptyColor: "#a4adb1"

    signal trackRequested(var track, int index)
    signal sortRequested(string key, bool descending)
    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)

    function positionViewAtBeginning() {
        list.positionViewAtBeginning();
    }

    CollectionTrackHeader {
        id: head
        width: parent.width
        showArtwork: true
        showAlbum: true
        labelColor: "#8d968e"
        activeColor: "#f0eee7"
        sortable: true
        sortKey: root.sortKey
        sortDescending: root.sortDescending
        onSortRequested: function(key, descending) { root.sortRequested(key, descending); }
    }

    DetailListView {
        id: list
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: head.bottom
        anchors.bottom: parent.bottom
        cacheBuffer: 400
        model: root.tracks

        delegate: CollectionTrackRow {
            id: row

            required property var modelData
            required property int index

            width: list.width
            track: modelData
            trackIndex: index
            showArtwork: true
            showAlbum: true
            onClicked: root.trackRequested(row.modelData, row.index)
            onAlbumRequested: function(album) { root.albumRequested(album); }
            onArtistRequested: function(artist) { root.artistRequested(artist); }
            onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
        }
    }

    Text {
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 440)
        visible: root.tracks.length === 0
        text: root.emptyText
        color: root.emptyColor
        font.family: "Inter"
        font.pixelSize: 13
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
    }
}
