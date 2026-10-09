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

import "../../components"
import "../../components/home"
import "../../components/detail"
import Orchard
import QtQuick
import QtQuick.Controls
import QtQuick.Effects

Item {
    id: root

    property bool sharedBackground: false
    property var detail: OrchardAlbum.detail || ({
    })
    property var artworkPalette: OrchardAlbum.palette || ({
    })
    readonly property var tracks: detail.tracks || []
    readonly property var downloadInfo: OrchardDownloads.revision >= 0 ? OrchardDownloads.collectionState(tracks) : ({})
    readonly property string downloadState: downloadInfo.total > 0 && downloadInfo.downloaded >= downloadInfo.total ? "done"
        : downloadInfo.pending > 0 ? "busy" : downloadInfo.downloaded > 0 ? "partial" : "none"
    readonly property color accentColor: rgb(artworkPalette.accent, [127, 190, 144])
    readonly property color accentSoftColor: rgb(artworkPalette.accentSoft, [150, 202, 164])
    readonly property color deepColor: rgb(artworkPalette.deep, [15, 21, 18])
    readonly property color inkColor: rgb(artworkPalette.ink, [8, 12, 10])
    readonly property color onAccentColor: rgb(artworkPalette.onAccent, [9, 16, 11])
    readonly property bool compact: width < 760
    readonly property int pageInset: width < 600 ? 14 : 24
    readonly property int sideWidth: 300
    readonly property int listLeft: compact ? 0 : pageInset + sideWidth + 40

    // Rows reveal on the first non-empty load. Removals, sorts and pages keep the list put.
    property bool hadTracks: false
    readonly property bool revealRows: !hadTracks || revealWindow.running
    function noteTracks() {
        if (tracks.length > 0 && !hadTracks)
            revealWindow.restart();
        hadTracks = tracks.length > 0;
    }
    onTracksChanged: noteTracks()
    Component.onCompleted: noteTracks()

    Timer {
        id: revealWindow
        interval: 400
    }

    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)
    signal backRequested()
    signal playRequested()
    signal shuffleRequested()
    signal trackRequested(var track, int index)

    function rgb(value, fallback) {
        const color = value && value.length >= 3 ? value : fallback;
        return Qt.rgba(Number(color[0]) / 255, Number(color[1]) / 255, Number(color[2]) / 255, 1);
    }

    function highResolutionArtwork(url) {
        let value = String(url || "");
        if (!value)
            return value;

        value = value.replace(/=w\d+-h\d+[^&]*/, "=w1200-h1200");
        value = value.replace(/w\d+-h\d+/g, "w1200-h1200");
        return value;
    }

    function tint(color, alpha) {
        return Qt.rgba(color.r, color.g, color.b, alpha);
    }

    // Sum of per-track durations; the provider leaves totalDuration empty.
    readonly property int totalSeconds: tracks.reduce((sum, track) => sum + (Number(track.durationSeconds) || 0), 0)
    readonly property string shareId: detail.audioPlaylistId || detail.playlistId || detail.browseId || detail.id || ""
    readonly property bool containsCurrent: {
        const id = OrchardPlayback.track.id;
        return Boolean(id) && tracks.some((track) => track.id === id);
    }
    readonly property bool collectionPlaying: containsCurrent && OrchardPlayback.playing

    function lengthLabel(seconds) {
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.round((seconds % 3600) / 60);
        if (hours > 0)
            return qsTr("%1 hr %2 min").arg(hours).arg(minutes);

        return qsTr("%1 min").arg(minutes);
    }

    function playOrToggle() {
        if (containsCurrent)
            OrchardPlayback.toggle();
        else
            root.playRequested();
    }

    focus: visible
    Keys.onReleased: function(event) {
        if (event.key === Qt.Key_Escape) {
            root.backRequested();
            event.accepted = true;
        }
    }

    Rectangle {
        anchors.fill: parent
        visible: !root.sharedBackground

        gradient: Gradient {
            GradientStop {
                position: 0
                color: root.deepColor
            }

            GradientStop {
                position: 1
                color: root.inkColor
            }

        }

    }

    // Wide: pinned left panel. Compact: stacked above the tracks (see heroSlot).
    DetailFlickable {
        id: sideScroll

        x: root.pageInset
        width: root.sideWidth
        height: parent.height
        visible: !root.compact
        contentWidth: width
        contentHeight: sideColumn.height + 28
        topMargin: 14
    }

    Column {
        id: sideColumn

        parent: root.compact ? heroSlot : sideScroll.contentItem
        y: root.compact ? 0 : 14
        width: root.compact ? root.width - root.pageInset * 2 : root.sideWidth
        spacing: 14

        Button {
            id: backButton

            width: 132
            height: 34
            text: qsTr("Back to home")
            onClicked: root.backRequested()

            background: Rectangle {
                radius: 17
                color: backButton.hovered ? "#18ffffff" : "transparent"
                border.color: backButton.activeFocus ? root.accentSoftColor : "transparent"
            }

            contentItem: Row {
                spacing: 8

                LucideIcon {
                    name: "chevron-left"
                    width: 16
                    height: 16
                    anchors.verticalCenter: parent.verticalCenter
                    color: root.accentSoftColor
                }

                Text {
                    text: backButton.text
                    color: root.accentSoftColor
                    font.family: "Inter"
                    font.pixelSize: 12
                    anchors.verticalCenter: parent.verticalCenter
                }

            }

        }

        CollectionHero {
            id: hero

            width: parent.width
            compact: root.compact
            kind: root.detail.releaseType || qsTr("Album")
            title: root.detail.title || qsTr("Album")
            byline: root.detail.artist || qsTr("Unknown artist")
            bylineInteractive: albumArtistMenu.artistIds.length > 0
            year: root.detail.year || ""
            meta: [
                root.tracks.length ? (root.tracks.length === 1 ? qsTr("1 song") : qsTr("%1 songs").arg(root.tracks.length)) : "",
                root.totalSeconds > 0 ? root.lengthLabel(root.totalSeconds) : ""
            ]
            description: root.detail.description || ""
            artwork: root.highResolutionArtwork(root.detail.thumbnail || "")
            animatedArtwork: OrchardAlbum.animatedArtworkUrl
            explicit: Boolean(root.detail.explicit)
            streamQuality: OrchardAlbum.streamQuality
            actionsEnabled: root.tracks.length > 0 && !OrchardAlbum.loading
            playing: root.collectionPlaying
            shareLabel: qsTr("Copy album.link")
            shareEnabled: Boolean(root.shareId)
            downloadable: root.tracks.length > 0
            downloadState: root.downloadState
            downloadEnabled: root.downloadState === "done"
                || (!OrchardAlbum.loading && !OrchardNetwork.offline && root.downloadState !== "busy")
            accentColor: root.accentColor
            accentSoftColor: root.accentSoftColor
            accentInkColor: root.onAccentColor
            inkColor: root.inkColor
            onBylineClicked: function(anchor, x, y) {
                albumArtistMenu.viewArtist(anchor, x, y);
            }
            onPlayClicked: root.playOrToggle()
            onShuffleClicked: root.shuffleRequested()
            onShareClicked: OrchardBackend.copySongLink(root.shareId, qsTr("album"))
            onShareOpenRequested: OrchardBackend.openSongLink(root.shareId, "album")
            onDownloadClicked: OrchardDownloads.downloadAll(root.tracks, Object.assign({}, root.detail, { kind: "album" }))
            onRemoveDownloadsClicked: OrchardDownloads.removeAll(root.tracks, OrchardOffline.collectionIdFor(root.detail))

            MediaMenu {
                id: albumArtistMenu

                media: root.detail
                onArtistRequested: function(artist) {
                    root.artistRequested(artist);
                }
                onPlaylistRequested: function(playlist) {
                    root.playlistRequested(playlist);
                }
            }

        }

    }

    DetailFlickable {
        id: scroll

        anchors.fill: parent
        anchors.leftMargin: root.listLeft
        clip: true
        contentWidth: width
        contentHeight: content.height + 28
        boundsBehavior: Flickable.StopAtBounds

        Column {
            id: content

            x: root.compact ? root.pageInset : 0
            y: 14
            width: scroll.width - (root.compact ? root.pageInset * 2 : root.pageInset)
            spacing: 14

            Item {
                id: heroSlot

                width: parent.width
                height: root.compact ? sideColumn.height : 0
            }

            Column {
                width: parent.width

                CollectionTrackHeader {
                    width: parent.width
                    showArtwork: false
                    labelColor: root.tint(root.accentSoftColor, 0.6)
                }

                Item {
                    width: 1
                    height: 6
                }

                Repeater {
                    model: root.tracks

                    delegate: CollectionTrackRow {
                        id: trackRow

                        required property var modelData
                        required property int index

                        width: parent.width
                        track: modelData
                        trackIndex: index
                        reveal: root.revealRows
                        showArtwork: false
                        // Features earn a second line; the headliner is already on the cover.
                        showArtist: trackRow.artistLabel !== (root.detail.artist || "")
                        fallbackArtist: root.detail.artist || ""
                        menuMedia: Object.assign({}, modelData, {
                            "albumId": root.detail.browseId
                        })
                        accentColor: root.accentColor
                        accentSoftColor: root.accentSoftColor
                        onClicked: root.trackRequested(trackRow.modelData, trackRow.index)
                        onAlbumRequested: function(album) {
                            root.albumRequested(album);
                        }
                        onArtistRequested: function(artist) {
                            root.artistRequested(artist);
                        }
                        onPlaylistRequested: function(playlist) {
                            root.playlistRequested(playlist);
                        }
                    }

                }

                Text {
                    width: parent.width
                    height: visible ? 72 : 0
                    visible: !root.tracks.length
                    text: qsTr("No tracks available for this album.")
                    color: root.accentSoftColor
                    verticalAlignment: Text.AlignVCenter
                    horizontalAlignment: Text.AlignHCenter
                    font.family: "Inter"
                    font.pixelSize: 13
                }

                // Liner notes, minus the smell of a fresh CD booklet.
                Text {
                    x: 12
                    width: parent.width - 24
                    topPadding: 18
                    visible: root.tracks.length > 0
                    text: [root.detail.year || "", root.tracks.length === 1 ? qsTr("1 song") : qsTr("%1 songs").arg(root.tracks.length), root.totalSeconds > 0 ? root.lengthLabel(root.totalSeconds) : ""].filter(Boolean).join("  ·  ")
                    color: root.tint(root.accentSoftColor, 0.55)
                    font.family: "Inter"
                    font.pixelSize: 12
                }

            }

        }

    }

    CollectionStickyBar {
        x: root.pageInset
        y: 8
        width: root.width - root.pageInset * 2
        shown: root.compact && !OrchardAlbum.loading && scroll.contentY > content.y + sideColumn.y + hero.y + hero.height - 40
        title: root.detail.title || qsTr("Album")
        artwork: root.detail.thumbnail || ""
        playing: root.collectionPlaying
        actionsEnabled: root.tracks.length > 0
        accentColor: root.accentColor
        accentInkColor: root.onAccentColor
        inkColor: root.inkColor
        onPlayClicked: root.playOrToggle()
        onBackToTopClicked: scroll.contentY = 0
    }

    PageLoadState {
        anchors.fill: parent
        loading: OrchardAlbum.loading
        errorMessage: OrchardAlbum.errorMessage
        accentColor: root.accentColor
        onRetryRequested: OrchardAlbum.retry()
    }

}
