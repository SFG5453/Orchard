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

import ".."
import QtQuick

Item {
    id: root

    required property var media
    property bool playable: false
    property bool playbackBlocked: false
    property bool navigable: false
    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)

    function openMenu(x, y) {
        menuLoader.active = true;
        Qt.callLater(() => {
            if (menuLoader.item)
                menuLoader.item.popup(root, x, y);
        });
    }

    Loader {
        id: menuLoader
        active: false

        sourceComponent: Component {
            MediaMenu {
                media: root.media
                onAlbumRequested: function(album) { root.albumRequested(album); }
                onArtistRequested: function(artist) { root.artistRequested(artist); }
                onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
                onClosed: menuLoader.active = false
            }
        }
    }
    TapHandler {
        acceptedButtons: Qt.RightButton
        onTapped: function(point) { root.openMenu(point.position.x, point.position.y); }
    }
    activeFocusOnTab: playable || playbackBlocked || navigable
    Accessible.role: Accessible.Button
    Accessible.name: media.title || ""
    Keys.onReturnPressed: if (playable || playbackBlocked || navigable) activated(media)
    Keys.onSpacePressed: if (playable || playbackBlocked || navigable) activated(media)
    Keys.onMenuPressed: root.openMenu(0, 0)
    signal activated(var song)
    TapHandler {
        enabled: root.songlist || root.playable || root.playbackBlocked || root.navigable
        onTapped: function(point) {
            // One handler owns the row so the ellipsis tap never also plays the song.
            if (root.songlist && root.overMore(point.position)) {
                root.openMenu(point.position.x, point.position.y);
                return;
            }
            if (root.playable || root.playbackBlocked || root.navigable)
                root.activated(root.media);
        }
    }

    function overMore(position) {
        const origin = more.mapToItem(root, 0, 0);
        return position.x >= origin.x && position.x <= origin.x + more.width
            && position.y >= origin.y && position.y <= origin.y + more.height;
    }

    property string presentation: "album"
    readonly property bool landscape: presentation === "landscape" || presentation === "editorial"
    readonly property bool compact: presentation === "compact"
    readonly property bool video: presentation === "video"
    // Apple-style song row: small art, hairline separator, ellipsis menu.
    readonly property bool songlist: presentation === "songlist"
    // Cards without hover fill or lift, used by the Home layout.
    property bool plain: false
    property real preferredWidth: 290
    property bool separator: false
    // Replaces the artist line, e.g. "2025 · Single" on an artist's own page.
    property string caption: ""
    // Album cards play their animated loop on hover when this names the artist.
    property string animatedArtist: ""
    readonly property bool hoverAnimates: animatedArtist.length > 0 && media.type === "album"
        && !songlist && OrchardAppearance.animatedArtworkEnabled
    readonly property bool nowPlaying: Boolean(media.id) && OrchardPlayback.track.id === media.id

    width: songlist || presentation === "album" ? preferredWidth : compact ? 290 : presentation === "editorial" ? 310 : landscape ? 238 : video ? 258 : presentation === "playlist" ? 178 : 158
    // Square art plus two text lines; video art stays 16:9 so grids can stretch cards.
    height: songlist ? 60 : compact ? 76 : landscape ? 162 : video ? Math.round(width * 9 / 16) + 57 : presentation === "playlist" ? 242 : width + 52

    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.NoButton
        cursorShape: root.playable || root.playbackBlocked || root.navigable ? Qt.PointingHandCursor : Qt.ArrowCursor
    }

    HoverHandler {
        id: hover
    }

    Rectangle {
        anchors.fill: parent
        radius: root.songlist ? 6 : 12
        color: hover.hovered && (root.songlist || !root.plain) ? (root.songlist ? "#0dffffff" : "#16ffffff") : "transparent"
        border.color: root.activeFocus ? "#96caa4" : "transparent"

        Behavior on color {
            ColorAnimation {
                duration: 140
            }
        }
    }

    Item {
        id: art

        x: root.songlist ? 4 : 0
        y: root.songlist ? 8 : root.plain || hover.hovered ? 0 : 2
        width: root.songlist ? 44 : root.compact ? 64 : root.width
        height: root.songlist ? 44 : root.compact ? 64 : root.landscape ? root.height - 4 : root.video ? Math.round(root.width * 9 / 16) : root.presentation === "playlist" ? 190 : root.width

        RoundedArtwork {
            anchors.fill: parent
            source: root.media.thumbnail || ""
            radius: root.songlist ? 4 : root.media.type === "artist" ? (root.plain ? width / 2 : 18) : root.plain ? 8 : 10
        }

        Timer {
            id: hoverDelay

            // Dwell before any lookup so sweeping the cursor across a shelf stays offline.
            interval: 400
            running: root.hoverAnimates && hover.hovered
        }

        Loader {
            anchors.fill: parent
            active: root.hoverAnimates && hover.hovered && !hoverDelay.running
            sourceComponent: HoverArtwork {
                title: root.media.title || ""
                artist: root.animatedArtist
                radius: root.compact ? 10 : root.plain ? 8 : 10
            }
        }

        Rectangle {
            anchors.fill: parent
            radius: 10
            visible: root.landscape || root.video

            gradient: Gradient {
                GradientStop {
                    position: 0
                    color: "#000f1215"
                }

                GradientStop {
                    position: 0.45
                    color: "#100f1215"
                }

                GradientStop {
                    position: 1
                    color: "#e80f1215"
                }
            }
        }

        Rectangle {
            id: durationBadge

            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.margins: 7
            width: durationLabel.implicitWidth + 10
            height: 20
            radius: 5
            visible: root.video && Boolean(root.media.duration)
            color: "#d90b100d"

            Text {
                id: durationLabel

                anchors.centerIn: parent
                text: root.media.duration || ""
                color: "#f4f3ed"
                font.family: "Inter"
                font.pixelSize: 10
                font.weight: Font.DemiBold
            }
        }

        Behavior on y {
            NumberAnimation {
                duration: 140
            }
        }
    }

    Column {
        x: root.songlist ? 60 : root.compact ? 78 : root.landscape ? 14 : 0
        y: root.songlist ? Math.round((root.height - height) / 2) : root.compact ? 13 : root.landscape ? root.height - 53 : art.height + 10
        width: root.width - x - (root.songlist ? 40 : root.landscape ? 14 : 4)
        spacing: root.songlist ? 3 : 5

        Row {
            width: parent.width
            spacing: 6
            Text {
                width: Math.min(implicitWidth, parent.width - (explicitBadge.visible ? 20 : 0))
                text: root.media.title || qsTr("Untitled")
                color: root.nowPlaying ? "#c4e0cb" : "#f2f0eb"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: root.plain ? Font.Medium : Font.DemiBold
                elide: Text.ElideRight
            }
            ExplicitBadge {
                id: explicitBadge
                visible: Boolean(root.media.explicit)
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        Text {
            width: parent.width
            text: root.caption || root.media.artist || (root.media.artists || []).join(", ") || root.media.subtitle || root.media.type || ""
            color: "#a4a9ad"
            font.family: "Inter"
            font.pixelSize: root.plain ? 12 : 11
            elide: Text.ElideRight
        }
    }

    Item {
        id: more

        anchors.right: parent.right
        anchors.rightMargin: 2
        anchors.verticalCenter: parent.verticalCenter
        width: 28
        height: 28
        visible: root.songlist

        HoverHandler {
            id: moreHover
        }

        Rectangle {
            anchors.fill: parent
            radius: width / 2
            color: moreHover.hovered ? "#18ffffff" : "transparent"
        }

        LucideIcon {
            anchors.centerIn: parent
            width: 16
            height: 16
            name: "ellipsis"
            color: moreHover.hovered ? "#f0eee7" : "#a3ada5"
        }
    }

    // Hairline between rows; the last row in each column goes without, like a well-behaved list.
    Rectangle {
        x: 4
        anchors.bottom: parent.bottom
        width: parent.width - 8
        height: 1
        color: "#14ffffff"
        visible: root.songlist && root.separator
    }
}
