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

import "../../components"
import "../../components/home"
import "../../components/detail"
import QtQuick

// Artist page. Layout lives in ArtistViewForm.ui.qml; this file binds the artist model and handles input.
// Backstage crew only: the form keeps the spotlight.
ArtistViewForm {
    id: root

    readonly property var sections: detail.sections || []
    readonly property var artistPalette: OrchardArtist.palette || ({})
    readonly property bool containsCurrent: {
        const id = OrchardPlayback.track.id;
        return Boolean(id) && tracks.some((track) => track.id === id);
    }

    signal backRequested()
    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)
    signal unsupportedRequested(var media)

    detail: OrchardArtist.detail || ({})
    tracks: detail.tracks || []
    // Discography first, then videos, then everyone else's shelves.
    orderedSections: sections
        .map((section, index) => ({ section, index }))
        .sort((left, right) => sectionRank(left.section) - sectionRank(right.section) || left.index - right.index)
        .map((entry) => entry.section)
    latestRelease: detail.latestRelease || null
    latestCaption: cardCaption(latestRelease || ({}))
    expandedKind: sectionKind(expandedSection || ({}))
    expandedAlbumWidth: albumWidthFor(expandedSection || ({}))
    loading: OrchardArtist.loading
    errorMessage: OrchardArtist.errorMessage
    browseIdentity: detail.browseId || ""
    collectionPlaying: containsCurrent && OrchardPlayback.playing
    playerInset: scroll.playerInset
    accentColor: rgb(artistPalette.accent, [127, 190, 144])
    accentSoftColor: rgb(artistPalette.accentSoft, [150, 202, 164])
    deepColor: rgb(artistPalette.deep, [15, 21, 18])
    inkColor: rgb(artistPalette.ink, [8, 12, 10])
    accentInkColor: rgb(artistPalette.onAccent, [9, 16, 11])

    function rgb(value, fallback) {
        const color = value && value.length >= 3 ? value : fallback;
        return Qt.rgba(Number(color[0]) / 255, Number(color[1]) / 255, Number(color[2]) / 255, 1);
    }

    function sectionKind(section) {
        const title = String(section && section.title || "").toLowerCase();
        if (/singles?|\beps?\b/.test(title))
            return "singles";
        if (/albums?/.test(title))
            return "albums";
        if (/videos?/.test(title))
            return "videos";
        if (/fans|similar|also like|related/.test(title))
            return "related";
        return "other";
    }

    function sectionRank(section) {
        return ["albums", "singles", "videos", "other", "related"].indexOf(sectionKind(section));
    }

    // Albums get the big covers; 65 singles would rather not.
    function albumWidthFor(section) {
        const kind = sectionKind(section);
        return kind === "albums" ? 184 : kind === "singles" ? 140 : 158;
    }

    function cardCaption(media) {
        if (media.releaseYear || media.releaseType)
            return [media.releaseYear, media.releaseType === "Album" ? "" : media.releaseType].filter(Boolean).join(" · ");
        // Every card here is theirs, so drop the artist's own name.
        const name = String(detail.title || "").toLowerCase();
        return String(media.subtitle || "").split(/\s*[•·]\s*/).filter((part) => part && part.toLowerCase() !== name).join(" · ");
    }

    function openSection(section) {
        expandedSection = section;
        sectionGrid.contentY = sectionGrid.originY;
        sectionGrid.forceActiveFocus();
    }

    function closeSection() {
        expandedSection = null;
        root.forceActiveFocus();
    }

    function activate(media) {
        if (media.type === "artist")
            artistRequested(media);
        else if (media.type === "album")
            albumRequested(media);
        else if (media.type === "playlist")
            playlistRequested(media);
    }

    function playOrToggle() {
        if (containsCurrent)
            OrchardPlayback.toggle();
        else
            OrchardPlayback.playCollection(tracks, 0, false);
    }

    onBrowseIdentityChanged: {
        expandedSection = null;
        scroll.contentY = 0;
    }

    Keys.onReleased: function(event) {
        if (event.key === Qt.Key_Escape) {
            if (root.expandedSection)
                root.closeSection();
            else
                root.backRequested();
            event.accepted = true;
        }
    }

    backButton.onClicked: root.backRequested()
    pageLoad.onRetryRequested: OrchardArtist.retry()
    moreButton.onClicked: aboutText.expanded = !aboutText.expanded

    hero.playButton.onClicked: root.playOrToggle()
    hero.shuffleButton.onClicked: OrchardPlayback.playCollection(root.tracks, 0, true)
    hero.shareButton.onClicked: OrchardBackend.copyToClipboard("https://music.youtube.com/channel/" + root.browseIdentity, qsTr("Copied artist link to clipboard"))

    stickyBar.onPlayClicked: root.playOrToggle()
    stickyBar.onBackToTopClicked: scroll.contentY = 0

    latestCard.onActivated: function(media) { root.activate(media); }
    latestCard.onAlbumRequested: function(album) { root.albumRequested(album); }
    latestCard.onArtistRequested: function(artist) { root.artistRequested(artist); }
    latestCard.onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }

    popularList.delegate: CollectionTrackRow {
        id: song

        required property var modelData
        required property int index

        width: parent.width
        track: modelData
        trackIndex: index
        showArtwork: true
        showAlbum: !root.compact
        // Features earn a second line; the headliner's name is already 76px tall.
        showArtist: song.artistLabel !== (root.detail.title || "")
        fallbackArtist: root.detail.title || ""
        menuMedia: Object.assign({}, modelData, {
            "artistBrowseIds": modelData.artistBrowseIds && modelData.artistBrowseIds.length ? modelData.artistBrowseIds : [root.browseIdentity]
        })
        accentColor: root.accentColor
        accentSoftColor: root.accentSoftColor
        onClicked: OrchardPlayback.playCollection(root.tracks, index, false)
        onAlbumRequested: function(album) { root.albumRequested(album); }
        onArtistRequested: function(artist) { root.artistRequested(artist); }
        onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
    }

    shelfList.delegate: Item {
        required property var modelData
        required property int index

        width: parent.width
        height: shelf.height + 18

        ShelfView {
            id: shelf

            y: 18
            width: parent.width
            section: modelData
            sectionIndex: index
            songsEnabled: true
            seeAllEnabled: true
            albumWidth: root.albumWidthFor(modelData)
            captionFor: root.cardCaption
            animatedArtist: root.detail.title || ""
            onSeeAllRequested: root.openSection(modelData)
            onSongRequested: function(song) {
                const items = modelData.items || [];
                const selectedIndex = items.findIndex(track => track.id === song.id);
                if (selectedIndex >= 0) OrchardPlayback.playCollection(items, selectedIndex);
                else OrchardPlayback.playSong(song);
            }
            onUnsupportedRequested: function(media) { root.unsupportedRequested(media); }
            onMediaRequested: function(media) { root.activate(media); }
            onAlbumRequested: function(album) { root.albumRequested(album); }
            onArtistRequested: function(artist) { root.artistRequested(artist); }
            onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
            onVerticalScrollRequested: function(delta, smooth) { root.scroll.scrollBy(delta, smooth); }
        }
    }

    sectionGrid.delegate: MediaCard {
        required property var modelData

        width: root.sectionGrid.cellWidth - 14
        media: modelData
        presentation: root.sectionGrid.presentation
        caption: root.cardCaption(modelData)
        animatedArtist: root.detail.title || ""
        playbackBlocked: media.type === "video"
        navigable: media.type === "album" || media.type === "artist" || media.type === "playlist"
        onActivated: function(media) {
            if (playbackBlocked)
                root.unsupportedRequested(media);
            else
                root.activate(media);
        }
        onAlbumRequested: function(album) { root.albumRequested(album); }
        onArtistRequested: function(artist) { root.artistRequested(artist); }
        onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
    }

    // The grid's Back button lives in its header delegate, so it is wired through the header item.
    Connections {
        // qmllint disable missing-property
        target: root.sectionGrid.headerItem ? root.sectionGrid.headerItem.backButton : null

        function onClicked() {
            root.closeSection();
        }
    }
}
