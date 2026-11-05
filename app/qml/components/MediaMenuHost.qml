/* Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import QtQuick
import "mediaNavigation.js" as Navigation

Item {
    id: root

    property var media: ({})
    property string removablePlaylistId: ""
    property string removablePlaylistTitle: ""
    readonly property string albumId: Navigation.albumId(media)
    readonly property var artistIds: Navigation.artistIds(media)
    readonly property bool opened: menu.item !== null && (menu.item.visible || menu.item.artistChoices.visible)

    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)

    function popup(anchor, x, y) {
        menu.active = true;
        menu.item.popup(anchor, x, y);
    }
    function viewAlbum() {
        if (albumId)
            albumRequested({browseId: albumId, title: media.album || (media.type === "album" ? media.title : "")});
    }
    function viewArtist(anchor, x, y) {
        if (artistIds.length === 1) {
            artistRequested(Navigation.artistAt(media, 0));
        } else if (artistIds.length > 1) {
            menu.active = true;
            menu.item.viewArtist(anchor, x, y);
        }
    }
    function release() {
        if (!opened) menu.active = false;
    }

    Loader {
        id: menu
        active: false
        sourceComponent: MediaMenu {
            media: root.media
            removablePlaylistId: root.removablePlaylistId
            removablePlaylistTitle: root.removablePlaylistTitle
            onAlbumRequested: album => root.albumRequested(album)
            onArtistRequested: artist => root.artistRequested(artist)
            onPlaylistRequested: playlist => root.playlistRequested(playlist)
            onClosed: Qt.callLater(root.release)
        }
    }
    Connections {
        target: menu.item ? menu.item.artistChoices : null
        function onClosed() { Qt.callLater(root.release); }
    }
}
