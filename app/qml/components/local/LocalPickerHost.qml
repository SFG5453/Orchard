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
import QtQuick.Dialogs
import QtQuick.Window

// The only owner of the file dialogs; see LocalPickers.
Item {
    id: root

    property string playlistId: ""
    property string ownerKind: ""
    property string ownerId: ""

    function addSongs(id) {
        root.playlistId = id;
        songDialog.open();
    }

    function addFolder(id) {
        root.playlistId = id;
        folderDialog.open();
    }

    function pickCover(kind, id) {
        root.ownerKind = kind;
        root.ownerId = id;
        coverDialog.open();
    }

    function pickLyrics(id) {
        root.ownerId = id;
        lyricsDialog.open();
    }

    visible: false
    // Dialogs need to find a window; the host is parked in the main one.
    Component.onCompleted: LocalPickers.host = root

    FileDialog {
        id: songDialog
        title: qsTr("Add songs")
        fileMode: FileDialog.OpenFiles
        nameFilters: OrchardLocal.audioNameFilters
        onAccepted: OrchardLocal.importFiles(selectedFiles, root.playlistId)
    }

    FolderDialog {
        id: folderDialog
        title: qsTr("Add a music folder")
        onAccepted: OrchardLocal.importFiles([selectedFolder], root.playlistId)
    }

    FileDialog {
        id: coverDialog
        title: qsTr("Choose a cover")
        nameFilters: OrchardLocal.imageNameFilters
        onAccepted: {
            if (root.ownerKind === "playlist")
                OrchardLocal.setPlaylistCover(root.ownerId, selectedFile);
            else
                OrchardLocal.setTrackCover(root.ownerId, selectedFile);
        }
    }

    FileDialog {
        id: lyricsDialog
        title: qsTr("Choose lyrics")
        nameFilters: OrchardLocal.lyricsNameFilters
        onAccepted: OrchardLocal.setTrackLyrics(root.ownerId, selectedFile)
    }
}
