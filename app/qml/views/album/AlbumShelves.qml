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
import "../../components/home"
import QtQuick

// Related shelves under an album's tracks: music videos, more by the artist, other versions.
Column {
    id: root

    property var sections: []

    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)
    signal videoRequested(var video)
    signal verticalScrollRequested(real delta, bool smooth)

    spacing: 18
    topPadding: sections.length ? 22 : 0

    Repeater {
        model: root.sections

        delegate: ShelfView {
            required property var modelData
            required property int index

            width: root.width
            section: modelData
            sectionIndex: index + 1
            songsEnabled: true
            onSongRequested: function(song) { OrchardPlayback.playSong(song); }
            onUnsupportedRequested: function(media) { root.videoRequested(media); }
            onMediaRequested: function(media) {
                if (media.type === "album")
                    root.albumRequested(media);
                else if (media.type === "artist")
                    root.artistRequested(media);
                else if (media.type === "playlist")
                    root.playlistRequested(media);
            }
            onAlbumRequested: function(album) { root.albumRequested(album); }
            onArtistRequested: function(artist) { root.artistRequested(artist); }
            onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
            onVerticalScrollRequested: function(delta, smooth) { root.verticalScrollRequested(delta, smooth); }
        }
    }
}
