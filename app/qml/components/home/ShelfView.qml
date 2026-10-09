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

import QtQuick
import QtQuick.Controls

import ".."

Item {
    id: root

    required property var section
    readonly property var items: section.items || []
    readonly property bool canGoBack: rail.contentX > 1
    readonly property bool canGoForward: rail.contentX < rail.contentWidth - rail.width - 1
    signal verticalScrollRequested(real delta, bool smooth)

    property bool songsEnabled: false
    // Home layout: song columns with hairlines, plain cards, and edge arrows on hover.
    property bool homeStyle: false
    // Header "See all" link; swaps the corner controls for hover edge arrows.
    property bool seeAllEnabled: false
    readonly property bool overflows: rail.contentWidth > rail.width + 1
    readonly property bool edgeArrows: homeStyle || seeAllEnabled
    // Square card width for album-style shelves.
    property int albumWidth: 158
    // Optional media => string override for each card's second line.
    property var captionFor: null
    // Artist whose albums animate on hover; empty leaves the shelf static.
    property string animatedArtist: ""
    signal seeAllRequested()
    signal songRequested(var song)
    signal unsupportedRequested(var media)
    signal mediaRequested(var media)
    signal artistRequested(var artist)
    signal albumRequested(var album)
    signal playlistRequested(var playlist)

    property int sectionIndex: 0
    property string presentation: {
        const type = String(items.length ? items[0].type || "" : "").toLowerCase();
        const title = String(section.title || "").toLowerCase();
        if (type === "album" || type === "artist")
            return "album";
        if (type === "playlist" || type === "mix" || type === "station")
            return /mood|activity|chill|focus/.test(title) ? "editorial" : "playlist";
        if (type === "video" || /\bvideos?\b/.test(title))
            return "video";
        if (homeStyle)
            return "songlist";
        return sectionIndex === 0 ? "landscape" : "compact";
    }
    readonly property bool songlist: presentation === "songlist"
    readonly property int rows: songlist ? Math.min(4, Math.max(1, items.length))
        : presentation === "compact" ? Math.min(4, Math.max(1, Math.ceil(items.length / 4))) : 1
    // Three columns and a peek of the fourth; two on narrow windows.
    readonly property real songColumnWidth: Math.max(240, Math.floor(width >= 1000 ? (width - 48) / 3 - 20 : (width - 24) / 2 - 28))
    readonly property int cellSpacing: songlist ? 24 : 14
    readonly property int cellW: songlist ? songColumnWidth
        : presentation === "compact" ? 290 : presentation === "editorial" ? 310 : presentation === "landscape" ? 238 : presentation === "video" ? 258 : presentation === "playlist" ? 178 : albumWidth
    readonly property int cellH: songlist ? 60
        : (presentation === "compact" ? 76 : presentation === "landscape" ? 162 : presentation === "video" ? 202 : presentation === "playlist" ? 242 : albumWidth + 52) + 14
    // Vertical center of the artwork, where the edge arrows sit.
    readonly property real artCenter: songlist ? rows * cellH / 2
        : presentation === "playlist" ? 95 : presentation === "video" ? 72 : presentation === "album" ? albumWidth / 2 : (cellH - 14) / 2
    implicitHeight: (rows * cellH) + 42

    function moveRail(direction) {
        scrollRailBy(-direction * Math.max(rail.width * (homeStyle ? 0.9 : 0.78), 190), true, false);
    }

    function scrollRailBy(delta, smooth, bounce = true) {
        if (delta === 0)
            return;
        if (smooth) {
            railAnimation.fling(-delta, bounce);
            return;
        }
        railAnimation.stop();
        const minimum = rail.originX;
        const maximum = Math.max(minimum, minimum + rail.contentWidth - rail.width);
        rail.contentX = Math.max(minimum, Math.min(rail.contentX - delta, maximum));
    }

    HoverHandler {
        id: shelfHover
        enabled: root.edgeArrows
    }

    Text {
        id: title
        anchors.left: parent.left
        anchors.top: parent.top
        width: Math.max(0, parent.width - (controls.visible ? controls.width + 18 : seeAll.visible ? seeAll.width + 18 : 0))
        text: root.section.title || qsTr("Music")
        color: "#f2efe7"
        font.family: "Inter"
        font.pixelSize: 20
        font.weight: root.homeStyle ? Font.Bold : Font.DemiBold
        elide: Text.ElideRight
    }

    Row {
        id: controls
        anchors.right: parent.right
        anchors.verticalCenter: title.verticalCenter
        spacing: 7
        visible: !root.edgeArrows

        Text {
            anchors.verticalCenter: parent.verticalCenter
            rightPadding: 2
            text: root.items.length
            color: "#777b72"
            font.family: "Inter"
            font.pixelSize: 10
        }

        Repeater {
            model: [
                {
                    icon: "chevron-left",
                    direction: -1
                },
                {
                    icon: "chevron-right",
                    direction: 1
                }
            ]

            Button {
                id: shelfButton
                required property var modelData
                readonly property bool usable: modelData.direction < 0 ? root.canGoBack : root.canGoForward

                width: 28
                height: 28
                implicitWidth: 28
                implicitHeight: 28
                hoverEnabled: true
                enabled: usable
                onClicked: root.moveRail(modelData.direction)

                background: Rectangle {
                    radius: width / 2
                    color: shelfButton.hovered && shelfButton.enabled ? "#34362f" : "#252720"
                    border.color: "#34362f"
                    opacity: shelfButton.enabled ? 1 : 0.42
                }

                contentItem: LucideIcon {
                    name: shelfButton.modelData.icon
                    color: shelfButton.usable ? "#dad8d0" : "#74776f"
                    implicitWidth: 14
                    implicitHeight: 14
                }
            }
        }
    }

    Button {
        id: seeAll

        anchors.right: parent.right
        anchors.verticalCenter: title.verticalCenter
        visible: root.seeAllEnabled && root.overflows
        hoverEnabled: true
        padding: 4
        text: qsTr("See all")
        Accessible.name: qsTr("See all %1").arg(root.section.title || "")
        onClicked: root.seeAllRequested()

        background: null

        contentItem: Row {
            spacing: 2

            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: seeAll.text
                color: seeAll.hovered ? "#f2efe7" : "#a4a9ad"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: Font.DemiBold
                font.underline: seeAll.visualFocus
            }

            LucideIcon {
                anchors.verticalCenter: parent.verticalCenter
                width: 14
                height: 14
                name: "chevron-right"
                color: seeAll.hovered ? "#f2efe7" : "#a4a9ad"
            }
        }
    }

    GridView {
        id: rail

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: title.bottom
        anchors.topMargin: root.songlist ? 10 : 14
        height: root.implicitHeight - 42
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flow: GridView.FlowTopToBottom

        cellWidth: root.cellW + root.cellSpacing
        cellHeight: root.cellH

        model: root.items
        cacheBuffer: 190

        WheelGlide {
            id: railAnimation
            view: rail
            horizontal: true
        }
        onDraggingChanged: if (dragging) railAnimation.stop()
        onVisibleChanged: railAnimation.stop()

        // Each handler listens to one axis; both use the same routing so a
        // diagonal gesture is consumed once by the appropriate view.
        RailWheelHandler {
            orientation: Qt.Vertical
            onVerticalRequested: function(delta, smooth) { root.verticalScrollRequested(delta, smooth); }
        }
        RailWheelHandler {
            orientation: Qt.Horizontal
            onHorizontalRequested: function(delta, smooth) { root.scrollRailBy(delta, smooth); }
        }

        delegate: MediaCard {
            required property var modelData
            required property int index
            media: modelData
            plain: root.homeStyle
            preferredWidth: root.cellW
            caption: root.captionFor ? root.captionFor(modelData) : ""
            animatedArtist: root.animatedArtist
            // Last row of each column drops its hairline.
            separator: index % root.rows !== root.rows - 1 && index !== root.items.length - 1
            playable: root.songsEnabled && root.presentation !== "video" && Boolean(media.id) && !media.unplayable &&
                (media.type === "song" || media.type === "track")
            playbackBlocked: root.songsEnabled && (root.presentation === "video" || media.type === "video")
            navigable: root.songsEnabled && (media.type === "album" || media.type === "artist" || media.type === "playlist")
            onAlbumRequested: function(album) { root.albumRequested(album); }
            onArtistRequested: function(artist) { root.artistRequested(artist); }
            onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
            onActivated: function(media) {
                if (playable)
                    root.songRequested(media);
                else if (playbackBlocked)
                    root.unsupportedRequested(media);
                else if (navigable)
                    root.mediaRequested(media);
            }
            presentation: root.presentation
        }
    }

    RailArrow {
        x: 8
        y: rail.y + root.artCenter - height / 2
        direction: -1
        shown: root.edgeArrows && shelfHover.hovered
        enabled: root.canGoBack
        onClicked: root.moveRail(-1)
    }

    RailArrow {
        x: root.width - width - 8
        y: rail.y + root.artCenter - height / 2
        direction: 1
        shown: root.edgeArrows && shelfHover.hovered
        enabled: root.canGoForward
        onClicked: root.moveRail(1)
    }
}
