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

Menu {
    id: root
    Component.onCompleted: configurePopup(root)
    width: 210
    padding: 5
    background: Rectangle { color: "#242c27"; radius: 10; border.color: "#455348" }
    // Grow from the click point so the menu reads as coming from the cursor.
    transformOrigin: Popup.TopLeft
    enter: Transition {
        NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Motion.fast; easing.type: Motion.enter }
        NumberAnimation { property: "scale"; from: 0.94; to: 1; duration: Motion.normal; easing.type: Motion.enter }
    }
    exit: Transition {
        NumberAnimation { property: "opacity"; to: 0; duration: Motion.fast; easing.type: Motion.exit }
    }
    component Entry: MenuItem {
        id: entry
        implicitHeight: 36
        background: Rectangle {
            radius: 6
            color: entry.highlighted ? "#435247" : "#00435247"
            Behavior on color { ColorAnimation { duration: Motion.fast } }
        }
        contentItem: Text {
            text: entry.text
            color: entry.enabled ? "#f2f0eb" : "#7c857f"
            font.family: "Inter"
            font.pixelSize: 13
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
    }
    property var media: ({})
    // Set by views of playlists the account can edit.
    property string removablePlaylistId: ""
    property string removablePlaylistTitle: ""
    readonly property bool isPlaylist: media.type === "playlist" || media.kind === "playlist"
    readonly property bool isArtist: media.type === "artist" || media.kind === "artist"
    readonly property bool isSong: ["song", "track", "video"].includes(media.type) && Boolean(media.id)
    // Files and playlists kept on this computer: no YouTube links, no sign-in needed.
    readonly property bool isLocalSong: isSong && String(media.id).indexOf("local:") === 0
    readonly property bool isLocalPlaylist: isPlaylist && String(media.id || "").indexOf("local-playlist:") === 0
    // "downloaded", "queued", "downloading", "failed" or "none"; the revision read refreshes it.
    readonly property string downloadState: isSong && !isLocalSong && OrchardDownloads.revision >= 0
        ? OrchardDownloads.stateOf(media.id) : "none"
    readonly property string albumId: media.type === "album" || media.kind === "album"
        ? (media.browsePayload || {}).browseId || media.browseId || media.albumId || ""
        : media.albumId || ""
    readonly property var artistIds: isArtist
        ? [media.browseId || (media.browsePayload || {}).browseId].filter(Boolean)
        : (media.artistBrowseIds && media.artistBrowseIds.length ? media.artistBrowseIds : (media.artistBrowseId || media.artistId ? [media.artistBrowseId || media.artistId] : []))
    readonly property string songLinkId: {
        if (Boolean(root.media.id) && (["song", "track", "video"].includes(root.media.type) || !root.media.type))
            return root.media.id;
        if (root.media.audioPlaylistId)
            return root.media.audioPlaylistId;
        if (root.media.playlistId)
            return root.media.playlistId;
        if (root.albumId)
            return root.albumId;
        if (root.media.browseId)
            return root.media.browseId;
        return "";
    }
    readonly property string songLinkKind: {
        if (Boolean(root.media.id) && (["song", "track", "video"].includes(root.media.type) || !root.media.type))
            return qsTr("song");
        if (root.media.audioPlaylistId || root.albumId || root.media.type === "album" || root.media.kind === "album")
            return qsTr("album");
        if (root.media.playlistId || root.media.type === "playlist" || root.media.kind === "playlist")
            return qsTr("playlist");
        return qsTr("song");
    }

    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)

    function configurePopup(menu) {
        // Qt 6.6 already draws popups in the scene. Newer Qt needs an explicit
        // Item preference to keep custom menus consistent across desktops.
        if ("popupType" in menu) menu["popupType"] = 0;
    }
    function viewAlbum() {
        if (albumId)
            albumRequested({browseId: albumId, title: media.album || (media.type === "album" ? media.title : "")});
    }
    function artistAt(index) {
        return {browseId: artistIds[index], type: "artist",
                title: (media.artists || [])[index] || media.artist || media.title || ""};
    }
    function viewArtist(anchor, x, y) {
        if (artistIds.length === 1)
            artistRequested(artistAt(0));
        else if (artistIds.length > 1)
            root.artistChoices.popup(anchor || root.parent, x === undefined ? root.x : x, y === undefined ? root.y : y);
    }
    // The shell owns the picker; this menu may be destroyed the moment it closes.
    function choosePlaylist() {
        const position = root.parent ? root.parent.mapToItem(null, root.x, root.y) : Qt.point(0, 0);
        OrchardLibrary.openPlaylistPicker(root.media, position);
    }

    Entry {
        text: qsTr("Play next")
        enabled: ["song", "track", "video"].includes(root.media.type) && Boolean(root.media.id) && !root.media.unplayable
        onTriggered: OrchardPlayback.enqueue(root.media, true)
    }
    Entry {
        text: qsTr("Add to queue")
        enabled: ["song", "track", "video"].includes(root.media.type) && Boolean(root.media.id) && !root.media.unplayable
        onTriggered: OrchardPlayback.enqueue(root.media)
    }
    Entry {
        text: qsTr("Add to playlist…")
        visible: root.isSong
        height: visible ? implicitHeight : 0
        enabled: root.isSong && !root.media.unplayable && (root.isLocalSong || (OrchardAuth.isSignedIn && !OrchardNetwork.offline))
        onTriggered: root.choosePlaylist()
    }
    Entry {
        text: root.downloadState === "downloaded" ? qsTr("Remove download")
            : root.downloadState === "queued" || root.downloadState === "downloading" ? qsTr("Cancel download")
            : root.downloadState === "failed" ? qsTr("Retry download") : qsTr("Download")
        visible: root.isSong && !root.isLocalSong
        height: visible ? implicitHeight : 0
        enabled: root.downloadState === "downloaded" || root.downloadState === "queued" || root.downloadState === "downloading"
            || (!root.media.unplayable && OrchardAuth.isSignedIn && !OrchardNetwork.offline)
        onTriggered: {
            if (root.downloadState === "downloaded")
                OrchardDownloads.remove(root.media.id);
            else if (root.downloadState === "queued" || root.downloadState === "downloading")
                OrchardDownloads.cancel(root.media.id);
            else
                OrchardDownloads.download(root.media);
        }
    }
    Entry {
        text: qsTr("Remove from this playlist")
        visible: root.isLocalSong && Boolean(root.media.localPlaylistId)
        height: visible ? implicitHeight : 0
        onTriggered: OrchardLocal.removeTrackById(root.media.localPlaylistId, root.media.id)
    }
    Entry {
        text: qsTr("Set cover…")
        visible: root.isLocalSong
        height: visible ? implicitHeight : 0
        onTriggered: LocalPickers.pickCover("track", root.media.id)
    }
    Entry {
        text: qsTr("Use the file's own cover")
        visible: root.isLocalSong && Boolean(root.media.hasCustomCover)
        height: visible ? implicitHeight : 0
        onTriggered: OrchardLocal.clearTrackCover(root.media.id)
    }
    Entry {
        text: qsTr("Set lyrics…")
        visible: root.isLocalSong
        height: visible ? implicitHeight : 0
        onTriggered: LocalPickers.pickLyrics(root.media.id)
    }
    Entry {
        text: qsTr("Remove custom lyrics")
        visible: root.isLocalSong && Boolean(root.media.hasCustomLyrics)
        height: visible ? implicitHeight : 0
        onTriggered: OrchardLocal.clearTrackLyrics(root.media.id)
    }
    Entry {
        text: qsTr("Remove from library")
        visible: root.isLocalSong
        height: visible ? implicitHeight : 0
        onTriggered: OrchardLocal.removeSong(root.media.id)
    }
    Entry {
        text: qsTr("Delete playlist")
        visible: root.isLocalPlaylist
        height: visible ? implicitHeight : 0
        onTriggered: OrchardLocal.deletePlaylist(root.media.id)
    }
    Entry {
        text: qsTr("Remove from this playlist")
        visible: root.isSong && root.removablePlaylistId.length > 0
        height: visible ? implicitHeight : 0
        onTriggered: OrchardLibrary.removeFromPlaylist(root.media, root.removablePlaylistId, root.removablePlaylistTitle)
    }
    Entry {
        text: qsTr("View album")
        enabled: Boolean(root.albumId) && !OrchardNetwork.offline
        onTriggered: root.viewAlbum()
    }
    // Playlist owners are users. Nobody needs the discography of a mixtape curator.
    Entry {
        text: qsTr("View artist")
        visible: !root.isPlaylist
        height: visible ? implicitHeight : 0
        enabled: root.artistIds.length > 0 && !OrchardNetwork.offline
        onTriggered: root.viewArtist()
    }
    // song.link has no artist pages, so artists get their channel. No knockoff merch here.
    Entry {
        text: qsTr("Copy artist link")
        visible: root.isArtist
        height: visible ? implicitHeight : 0
        enabled: root.artistIds.length > 0
        onTriggered: OrchardBackend.copyToClipboard("https://music.youtube.com/channel/" + root.artistIds[0], qsTr("Copied artist link to clipboard"))
    }
    // Sharing is caring, and song.link bridges the eternal Apple vs Spotify holy war.
    Entry {
        visible: !root.isArtist && !root.isLocalSong && !root.isLocalPlaylist
        height: visible ? implicitHeight : 0
        text: root.songLinkKind === qsTr("album")
            ? qsTr("Copy album.link")
            : (root.songLinkKind === qsTr("playlist") ? qsTr("Copy playlist link") : qsTr("Copy song.link"))
        enabled: Boolean(root.songLinkId)
        onTriggered: OrchardBackend.copySongLink(root.songLinkId, root.songLinkKind)
    }
    // Collaborations deserve a guest list
    property Menu artistChoices: Menu {
        title: qsTr("Artists")
        parent: root.parent
        Component.onCompleted: root.configurePopup(root.artistChoices)
        width: 230
        padding: 5
        background: Rectangle { color: "#242c27"; radius: 10; border.color: "#455348" }
        transformOrigin: Popup.TopLeft
        enter: Transition {
            NumberAnimation { property: "opacity"; from: 0; to: 1; duration: Motion.fast; easing.type: Motion.enter }
            NumberAnimation { property: "scale"; from: 0.94; to: 1; duration: Motion.normal; easing.type: Motion.enter }
        }
        exit: Transition {
            NumberAnimation { property: "opacity"; to: 0; duration: Motion.fast; easing.type: Motion.exit }
        }
        Instantiator {
            model: root.artistIds
            delegate: Entry {
                required property int index
                text: root.artistAt(index).title || qsTr("Artist %1").arg(index + 1)
                onTriggered: root.artistRequested(root.artistAt(index))
            }
            onObjectAdded: function(index, object) { root.artistChoices.insertItem(index, object); }
            onObjectRemoved: function(index, object) { root.artistChoices.removeItem(object); }
        }
    }
}
