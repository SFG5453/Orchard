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
import ".."
import "../home"

// One song in an album or playlist. Must stay cheap: playlists run into the thousands.
ItemDelegate {
    id: root

    property var track: ({})
    property int trackIndex: 0
    // Views raise this while a freshly loaded list settles; rows built later by scrolling skip it.
    property bool reveal: false
    property bool showArtwork: true
    property bool showAlbum: false
    property bool showArtist: true
    property bool searchMatch: false
    property string fallbackArtist
    property var menuMedia: track
    property string removablePlaylistId: ""
    property string removablePlaylistTitle: ""
    property color accentColor: "#7fbe90"
    property color accentSoftColor: "#96caa4"

    readonly property bool current: Boolean(OrchardPlayback.track.id) && OrchardPlayback.track.id === track.id
    // The revision read makes the icon follow downloads finishing or being removed.
    readonly property bool downloaded: OrchardDownloads.revision >= 0 && OrchardDownloads.isDownloaded(track.id || "")
    readonly property real albumWidth: showAlbum ? Math.round(width * 0.26) : 0
    readonly property string artistLabel: (track.artists && track.artists.length ? track.artists.join(", ") : "")
                                          || track.artist
                                          || fallbackArtist
                                          || qsTr("Unknown artist")

    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)

    function tint(color, alpha) { return Qt.rgba(color.r, color.g, color.b, alpha); }

    height: showArtwork ? 60 : 54
    padding: 0
    hoverEnabled: true
    Accessible.name: (trackIndex + 1) + ". " + (track.title || qsTr("Untitled track")) + ", " + artistLabel
                     + (downloaded ? ", " + qsTr("downloaded") : "")

    MediaMenu {
        id: trackMenu
        media: root.menuMedia
        removablePlaylistId: root.removablePlaylistId
        removablePlaylistTitle: root.removablePlaylistTitle
        onAlbumRequested: function(album) { root.albumRequested(album); }
        onArtistRequested: function(artist) { root.artistRequested(artist); }
        onPlaylistRequested: function(playlist) { root.playlistRequested(playlist); }
    }
    TapHandler {
        acceptedButtons: Qt.RightButton
        onTapped: function(point) { trackMenu.popup(root, point.position.x, point.position.y); }
    }

    background: Rectangle {
        radius: 10
        color: root.searchMatch ? root.tint(root.accentColor, 0.3)
             : root.current ? root.tint(root.accentColor, 0.14)
             : root.hovered || trackMenu.visible ? "#10ffffff" : "transparent"
        border.width: root.searchMatch ? 2 : 1
        border.color: root.searchMatch ? root.accentColor
                    : root.activeFocus ? root.accentSoftColor
                    : root.current ? root.tint(root.accentSoftColor, 0.14) : "transparent"
        Behavior on color { ColorAnimation { duration: 110 } }
    }

    Component.onCompleted: if (reveal) { opacity = 0; entrance.start(); }
    // Capped at a dozen rows so the back of the line doesn't sit through the whole opening act.
    SequentialAnimation {
        id: entrance
        PauseAnimation { duration: Math.min(root.trackIndex, 12) * Motion.stagger }
        NumberAnimation { target: root; property: "opacity"; to: 1; duration: Motion.normal; easing.type: Motion.enter }
    }

    contentItem: Item {
        Item {
            id: numberCell
            x: 12
            width: 32
            height: parent.height

            Text {
                anchors.centerIn: parent
                visible: !root.hovered && !root.current
                text: root.trackIndex + 1
                color: "#8c928c"
                font.family: "Inter"
                font.pixelSize: 13
                font.features: { "tnum": 1 }
            }
            LucideIcon {
                anchors.centerIn: parent
                visible: root.hovered && !root.current
                name: "play"
                width: 16
                height: 16
                color: "white"
            }
            // Three dancing bars: the universal sign for "yes, this one".
            Loader {
                anchors.centerIn: parent
                active: root.current
                sourceComponent: Item {
                    width: 14
                    height: 14
                    Repeater {
                        model: [0.9, 0.55, 0.75]
                        Rectangle {
                            id: bar
                            required property real modelData
                            required property int index
                            x: index * 5
                            y: parent.height - height
                            width: 3
                            radius: 1.5
                            height: 14 * modelData
                            color: root.accentSoftColor
                            SequentialAnimation on height {
                                running: OrchardPlayback.playing
                                loops: Animation.Infinite
                                NumberAnimation { to: 3; duration: 260 + bar.index * 90; easing.type: Easing.InOutSine }
                                NumberAnimation { to: 14; duration: 300 + bar.index * 70; easing.type: Easing.InOutSine }
                                NumberAnimation { to: 14 * bar.modelData; duration: 240 + bar.index * 60; easing.type: Easing.InOutSine }
                            }
                        }
                    }
                }
            }
        }

        RoundedArtwork {
            id: art
            x: numberCell.x + numberCell.width + 14
            anchors.verticalCenter: parent.verticalCenter
            visible: root.showArtwork
            width: visible ? 44 : 0
            height: 44
            radius: 7
            source: root.showArtwork ? (root.track.thumbnail || "") : ""
        }

        Column {
            id: titleBlock
            x: root.showArtwork ? art.x + art.width + 14 : numberCell.x + numberCell.width + 14
            width: durationText.x - x - 16 - (root.showAlbum ? root.albumWidth + 16 : 0)
            anchors.verticalCenter: parent.verticalCenter
            spacing: 3

            Row {
                width: parent.width
                spacing: 6
                Text {
                    width: Math.min(implicitWidth, parent.width - (badge.visible ? badge.width + 6 : 0)
                                    - (slopBadge.visible ? slopBadge.width + 6 : 0)
                                    - (downloadedIcon.visible ? downloadedIcon.width + 6 : 0))
                    text: root.track.title || qsTr("Untitled track")
                    color: root.current ? root.accentSoftColor : "#f1f2ee"
                    font.family: "Inter"
                    font.pixelSize: 14
                    font.weight: Font.Medium
                    elide: Text.ElideRight
                }
                ExplicitBadge {
                    id: badge
                    visible: Boolean(root.track.explicit)
                    anchors.verticalCenter: parent.verticalCenter
                    color: "#26ffffff"
                    textColor: "#d6d9d4"
                }
                SlopBadge {
                    id: slopBadge
                    trackId: root.track.id || ""
                    anchors.verticalCenter: parent.verticalCenter
                }
                // Saved on this computer, so it plays without a connection.
                LucideIcon {
                    id: downloadedIcon
                    visible: root.downloaded
                    anchors.verticalCenter: parent.verticalCenter
                    width: 14
                    height: 14
                    name: "circle-arrow-down"
                    color: root.accentSoftColor
                }
            }
            Text {
                visible: root.showArtist
                width: Math.min(implicitWidth, parent.width)
                text: root.artistLabel
                color: artistMouse.containsMouse ? "white" : "#9ba19a"
                font.family: "Inter"
                font.pixelSize: 12
                font.underline: artistMouse.containsMouse
                elide: Text.ElideRight
                MouseArea {
                    id: artistMouse
                    anchors.fill: parent
                    enabled: trackMenu.artistIds.length > 0 && !OrchardNetwork.offline
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: function(mouse) { trackMenu.viewArtist(parent, mouse.x, mouse.y); }
                }
            }
        }

        Text {
            x: durationText.x - 16 - root.albumWidth
            width: Math.min(implicitWidth, root.albumWidth)
            visible: root.showAlbum
            anchors.verticalCenter: parent.verticalCenter
            text: root.track.album || ""
            color: albumMouse.containsMouse ? "white" : "#9ba19a"
            font.family: "Inter"
            font.pixelSize: 12
            font.underline: albumMouse.containsMouse
            elide: Text.ElideRight
            MouseArea {
                id: albumMouse
                anchors.fill: parent
                enabled: Boolean(trackMenu.albumId) && !OrchardNetwork.offline
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: trackMenu.viewAlbum()
            }
        }

        Text {
            id: durationText
            x: moreButton.x - width
            width: 48
            anchors.verticalCenter: parent.verticalCenter
            horizontalAlignment: Text.AlignRight
            text: root.track.duration || "–"
            color: "#8c928c"
            font.family: "Inter"
            font.pixelSize: 12
            font.features: { "tnum": 1 }
        }

        ToolButton {
            id: moreButton
            x: parent.width - 8 - width
            width: 36
            height: 36
            anchors.verticalCenter: parent.verticalCenter
            padding: 10
            opacity: root.hovered || trackMenu.visible || activeFocus ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 110 } }
            Accessible.name: qsTr("More options for %1").arg(root.track.title || qsTr("track"))
            onClicked: trackMenu.popup(moreButton, 0, moreButton.height)
            background: Rectangle {
                radius: width / 2
                color: moreButton.hovered ? "#1fffffff" : "transparent"
            }
            contentItem: LucideIcon { name: "ellipsis"; color: "#e6e8e3" }
        }
    }
}
