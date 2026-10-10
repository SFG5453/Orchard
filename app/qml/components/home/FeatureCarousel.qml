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

import Orchard
import QtQuick

import ".."

// Top Picks rail at the top of Home: cover on top, a footer in the cover's edge color below.
Item {
    id: root

    // Entries are { eyebrow, media }.
    property var features: []
    readonly property bool canGoBack: rail.contentX > rail.originX + 1
    readonly property bool canGoForward: rail.contentX < rail.originX + rail.contentWidth - rail.width - 1
    // Full cards plus a 30% peek of the next.
    readonly property int columns: width >= 1300 ? 5 : width >= 900 ? 4 : width >= 640 ? 3 : 2
    // Floor keeps the zero-width first layout positive; negative delegates leave the rail scrolled to the end.
    readonly property int cardWidth: Math.max(120, Math.floor((width - rail.spacing * columns) / (columns + 0.3)))
    readonly property int footerHeight: 78
    readonly property int radius: 10

    signal mediaRequested(var media)
    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)
    signal verticalScrollRequested(real delta, bool smooth)

    implicitHeight: cardWidth + footerHeight

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

    function subtitleFor(media) {
        return (media.artists || []).join(", ") || media.artist || media.subtitle || "";
    }

    // Untyped on purpose: qmlcachegen 6.11 segfaults on `(map || {}).seam` in a compiled binding.
    function paletteColor(colors, key, fallback) {
        const value = (colors || {})[key];
        return value && value.length >= 3 ? Qt.rgba(value[0] / 255, value[1] / 255, value[2] / 255, 1) : fallback;
    }

    HoverHandler {
        id: hover
    }

    ListView {
        id: rail

        anchors.fill: parent
        orientation: ListView.Horizontal
        spacing: 16
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        model: root.features
        cacheBuffer: 600

        WheelGlide {
            id: railAnimation
            view: rail
            horizontal: true
        }
        onDraggingChanged: if (dragging) railAnimation.stop()

        RailWheelHandler {
            orientation: Qt.Vertical
            onVerticalRequested: function(delta, smooth) { root.verticalScrollRequested(delta, smooth); }
        }
        RailWheelHandler {
            orientation: Qt.Horizontal
            onHorizontalRequested: function(delta, smooth) { root.scrollRailBy(delta, smooth); }
        }

        delegate: Item {
            id: card

            required property var modelData
            readonly property var media: modelData.media || ({})
            readonly property var coverColors: coverPalette.palette
            // Edge color is clamped dark by the sampler, so white text always reads.
            readonly property color footerColor: root.paletteColor(coverColors, "seam", "#252a2c")

            function openMenu(x, y) {
                menuLoader.active = true;
                Qt.callLater(() => {
                    if (menuLoader.item)
                        menuLoader.item.popup(card, x, y);
                });
            }

            width: root.cardWidth
            height: rail.height
            activeFocusOnTab: true
            Accessible.role: Accessible.Button
            Accessible.name: media.title || ""
            Keys.onReturnPressed: root.mediaRequested(card.media)
            Keys.onSpacePressed: root.mediaRequested(card.media)
            Keys.onMenuPressed: card.openMenu(0, 0)

            ArtworkPalette {
                id: coverPalette
                source: card.media.thumbnail || ""
            }

            Loader {
                id: menuLoader
                active: false

                sourceComponent: Component {
                    MediaMenu {
                        media: card.media
                        onAlbumRequested: function(album) { root.albumRequested(album); }
                        onArtistRequested: function(artist) { root.artistRequested(artist); }
                        onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
                        onClosed: menuLoader.active = false
                    }
                }
            }

            HoverHandler {
                id: cardHover
                cursorShape: Qt.PointingHandCursor
            }

            TapHandler {
                onTapped: root.mediaRequested(card.media)
            }

            TapHandler {
                acceptedButtons: Qt.RightButton
                onTapped: function(point) { card.openMenu(point.position.x, point.position.y); }
            }

            RoundedArtwork {
                id: cover

                width: parent.width
                height: width
                radius: root.radius
                bottomRadius: 0
                source: card.media.thumbnail || ""
            }

            Rectangle {
                anchors.top: cover.bottom
                width: parent.width
                height: root.footerHeight
                radius: root.radius
                topLeftRadius: 0
                topRightRadius: 0
                color: card.footerColor

                Behavior on color {
                    ColorAnimation { duration: Motion.normal }
                }

                Column {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    spacing: 2

                    Text {
                        width: parent.width
                        text: card.modelData.eyebrow || ""
                        color: "#a6ffffff"
                        font.family: "Inter"
                        font.pixelSize: 11
                        elide: Text.ElideRight
                    }

                    Text {
                        width: parent.width
                        text: card.media.title || qsTr("Untitled")
                        color: "#f5f3ee"
                        font.family: "Inter"
                        font.pixelSize: 13
                        font.weight: Font.DemiBold
                        elide: Text.ElideRight
                    }

                    Text {
                        width: parent.width
                        text: root.subtitleFor(card.media)
                        color: "#b8ffffff"
                        font.family: "Inter"
                        font.pixelSize: 12
                        elide: Text.ElideRight
                    }
                }
            }

            // Hover sheen and focus ring over the whole card. No blur was harmed in the making of this card.
            Rectangle {
                anchors.fill: parent
                radius: root.radius
                color: cardHover.hovered ? "#14ffffff" : "transparent"
                border.color: card.activeFocus ? "#96caa4" : "transparent"

                Behavior on color {
                    ColorAnimation { duration: Motion.fast }
                }
            }
        }
    }

    RailArrow {
        x: 8
        y: (root.cardWidth - height) / 2
        direction: -1
        shown: hover.hovered
        enabled: root.canGoBack
        onClicked: root.scrollRailBy(Math.max(rail.width * 0.9, 200), true, false)
    }

    RailArrow {
        x: root.width - width - 8
        y: (root.cardWidth - height) / 2
        direction: 1
        shown: hover.hovered
        enabled: root.canGoForward
        onClicked: root.scrollRailBy(-Math.max(rail.width * 0.9, 200), true, false)
    }
}
