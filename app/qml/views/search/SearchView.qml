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

import Orchard
import QtQuick
import QtQuick.Controls
import "../../components"
import "../../components/home"

Item {
    id: root
    property string query: ""
    property string filter: "all"
    property bool loading: false
    property string errorMessage: ""
    property var sections: []
    // Offline search covers downloaded and local songs and playlists only.
    readonly property var filters: OrchardNetwork.offline
        ? [{label: qsTr("All"), value: "all"}, {label: qsTr("Songs"), value: "songs"}, {label: qsTr("Playlists"), value: "playlists"}]
        : [
            {label: qsTr("All"), value: "all"}, {label: qsTr("Songs"), value: "songs"},
            {label: qsTr("Videos"), value: "videos"}, {label: qsTr("Albums"), value: "albums"},
            {label: qsTr("Artists"), value: "artists"}, {label: qsTr("Playlists"), value: "playlists"}
        ]
    readonly property int resultCount: sections.reduce((n, s) => n + (s.items || []).length, 0)
    readonly property var topResult: ((sections.find(s => s.key === "songs") || sections[0] || {}).items || [])[0] || ({})
    signal filterRequested(string filter)
    signal retryRequested
    signal songRequested(var song)
    signal unsupportedRequested(var media)
    signal albumRequested(var album)
    signal artistRequested(var artist)
    signal playlistRequested(var playlist)
    function credit(m) { return (m.artists || []).join(", ") || m.artist || m.subtitle || ""; }
    function activate(m, category) {
        if (category === "videos" || m.type === "video") unsupportedRequested(m);
        else if (m.type === "album") albumRequested(m);
        else if (m.type === "artist") artistRequested(m);
        else if (m.type === "playlist") playlistRequested(m);
        else if (m.id && !m.unplayable) songRequested(m);
    }
    onSectionsChanged: { scroll.contentY = 0; if (resultCount > 0) resultsEntrance.restart(); }
    // Results arrive in one batch; lift the column in, then let rows trickle after it.
    ParallelAnimation {
        id: resultsEntrance
        NumberAnimation { target: content; property: "opacity"; from: 0; to: 1; duration: Motion.normal; easing.type: Motion.enter }
        NumberAnimation { target: contentShift; property: "y"; from: Motion.rise; to: 0; duration: Motion.slow; easing.type: Motion.enter }
    }

    component Caption: Text {
        color: "#f2f0eb"; font.family: "Inter"; font.pixelSize: 12; elide: Text.ElideRight
    }
    component Panel: Rectangle { radius: 13; color: "#10ffffff"; border.color: "#14ffffff" }
    component Options: Button {
        id: options
        property var media: ({})
        width: 26; height: 32
        Accessible.name: qsTr("More options for %1").arg(media.title || "")
        onClicked: menu.popup(options, 0, height)
        background: Rectangle { radius: 7; color: options.hovered || options.activeFocus ? "#20ffffff" : "transparent" }
        contentItem: Caption { text: "⋮"; font.pixelSize: 22; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
        MediaMenu {
            id: menu; media: options.media
            onAlbumRequested: album => root.albumRequested(album)
            onArtistRequested: artist => root.artistRequested(artist)
            onPlaylistRequested: playlist => root.playlistRequested(playlist)
        }
    }
    component Duration: Rectangle {
        property string text: ""
        visible: Boolean(text); width: clockLabel.implicitWidth + 8; height: 18; radius: 4; color: "#d9000000"
        Caption { id: clockLabel; anchors.centerIn: parent; text: parent.text; font.pixelSize: 10; font.weight: Font.DemiBold }
    }
    Row {
        id: tabs
        height: 48; spacing: 8
        Repeater {
            model: root.filters
            Button {
                id: tab
                required property var modelData
                implicitWidth: tabText.implicitWidth + 30; height: 32
                onClicked: root.filterRequested(modelData.value)
                background: Rectangle {
                    radius: 16
                    color: root.filter === tab.modelData.value ? "#f1f0ec" : tab.hovered ? "#20ffffff" : "#08ffffff"
                    border.color: tab.activeFocus ? "#b8d4bd" : "#18ffffff"
                    Behavior on color { ColorAnimation { duration: Motion.normal } }
                }
                contentItem: Caption {
                    id: tabText; text: tab.modelData.label
                    color: root.filter === tab.modelData.value ? "#171b17" : "#d2d1cc"
                    Behavior on color { ColorAnimation { duration: Motion.normal } }
                    horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
                }
            }
        }
    }
    Caption {
        anchors.right: parent.right; y: 10; visible: root.width > 780
        text: root.loading ? qsTr("Searching…") : root.resultCount ? qsTr("%1 results").arg(root.resultCount) : ""
        color: "#b8b9b3"; font.pixelSize: 11
    }
    DetailFlickable {
        id: scroll
        anchors.top: tabs.bottom; anchors.bottom: parent.bottom; anchors.left: parent.left; anchors.right: parent.right
        contentWidth: width; contentHeight: content.height + 20; visible: root.resultCount > 0
        Column {
            id: content
            width: scroll.width; spacing: 10
            transform: Translate { id: contentShift }
            Panel {
                width: parent.width; height: root.width > 1000 ? 174 : 154; visible: root.filter === "all"
                Button {
                    id: hero
                    anchors.fill: parent; anchors.margins: 12
                    onClicked: root.activate(root.topResult, "")
                    Accessible.name: root.topResult.title || ""
                    background: Rectangle { radius: 8; color: hero.hovered ? "#08ffffff" : "transparent"; border.color: hero.activeFocus ? "#b8d4bd" : "transparent" }
                    contentItem: Item {
                        RoundedArtwork {
                            id: heroArt
                            width: root.topResult.type === "video" ? height * 16 / 9 : height
                            height: parent.height; radius: root.topResult.type === "artist" ? height / 2 : 8
                            source: root.topResult.thumbnail || ""
                            Rectangle {
                                anchors.centerIn: parent; width: 48; height: 48; radius: 24; color: "#88504a38"
                                LucideIcon { anchors.centerIn: parent; width: 23; height: 23; name: "play"; color: "#ffffff" }
                            }
                            Duration { anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: 6; text: root.topResult.duration || "" }
                        }
                        Column {
                            anchors.left: heroArt.right; anchors.leftMargin: 26; anchors.right: parent.right; anchors.rightMargin: 32
                            anchors.verticalCenter: parent.verticalCenter; spacing: 6
                            Caption { text: qsTr("Top result"); color: "#bdbdb5"; font.pixelSize: 11 }
                            Caption { width: parent.width; text: root.topResult.title || ""; font.pixelSize: 24; font.weight: Font.DemiBold }
                            Caption { width: parent.width; text: root.credit(root.topResult); font.pixelSize: 17; color: "#dfdfd8" }
                            Caption { width: parent.width; text: [root.topResult.views, root.topResult.year].filter(Boolean).join(" • "); visible: Boolean(text); color: "#bcbdb6" }
                            Caption { width: parent.width; text: root.topResult.description || root.topResult.album || ""; visible: Boolean(text); color: "#bcbdb6"; maximumLineCount: 2; wrapMode: Text.Wrap }
                        }
                    }
                }
                Options { anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: 10; media: root.topResult }
            }
            Repeater {
                model: [
                    root.sections.filter(s => ["songs", "videos", "artists"].includes(s.key)),
                    root.sections.filter(s => ["albums", "playlists"].includes(s.key))
                ]
                Flow {
                    id: group
                    required property var modelData
                    width: content.width; spacing: 10
                    Repeater {
                        model: group.modelData
                        Panel {
                            id: panel
                            required property var modelData
                            readonly property bool tiles: ["albums", "playlists"].includes(modelData.key)
                            readonly property bool coverGrid: tiles && root.filter !== "all"
                            readonly property var entries: root.filter === "all" ? modelData.items.slice(0, 5) : modelData.items
                            // Dedicated tabs give covers room to breathe. No panoramic album pancakes.
                            readonly property int columns: tiles ? (coverGrid ? Math.max(2, Math.floor((width - 14) / 220)) : 5) : 1
                            width: root.filter === "all" && root.width >= 900
                                ? (group.width - (group.modelData.length - 1) * group.spacing) / Math.max(1, group.modelData.length)
                                : group.width
                            height: !tiles && root.filter === "all" ? 320 : results.y + results.height + 10
                            Caption { x: 12; y: 10; text: panel.modelData.title; font.pixelSize: 18; font.weight: Font.DemiBold }
                            Button {
                                id: viewAll
                                anchors.right: parent.right; anchors.rightMargin: 10; y: 8; height: 26
                                visible: root.filter === "all"
                                onClicked: root.filterRequested(panel.modelData.key)
                                background: Rectangle { radius: 7; color: viewAll.hovered || viewAll.activeFocus ? "#18ffffff" : "transparent" }
                                contentItem: Caption { text: qsTr("View all  ›"); color: "#c9cac2"; font.pixelSize: 11; verticalAlignment: Text.AlignVCenter }
                            }
                            Flow {
                                id: results
                                x: panel.tiles ? 14 : 6; y: 40; width: parent.width - x * 2; spacing: panel.tiles ? 14 : 0
                                Repeater {
                                    model: panel.entries
                                    Item {
                                        id: entry
                                        required property var modelData
                                        required property int index
                                        // Cap the stagger so a 50-row tab doesn't make the last row wait a second and a half.
                                        opacity: 0
                                        SequentialAnimation on opacity {
                                            PauseAnimation { duration: Math.min(entry.index, 8) * Motion.stagger }
                                            NumberAnimation { to: 1; duration: Motion.normal; easing.type: Motion.enter }
                                        }
                                        readonly property bool video: panel.modelData.key === "videos"
                                        readonly property bool artist: panel.modelData.key === "artists"
                                        width: panel.tiles ? (results.width - (panel.columns - 1) * results.spacing) / panel.columns : results.width
                                        height: panel.tiles ? width * (panel.coverGrid ? 1 : 0.66) + 70 : 54
                                        Button {
                                            id: itemButton
                                            anchors.fill: parent; padding: 0
                                            onClicked: root.activate(entry.modelData, panel.modelData.key)
                                            Accessible.name: entry.modelData.title || ""
                                            background: Rectangle { radius: 8; color: itemButton.hovered ? "#23ffffff" : "transparent"; border.color: itemButton.activeFocus ? "#b8d4bd" : "transparent" }
                                            contentItem: Item {
                                                RoundedArtwork {
                                                    id: art
                                                    x: panel.tiles ? 0 : 5; y: panel.tiles ? 0 : 5
                                                    width: panel.tiles ? parent.width : entry.video ? 88 : 44
                                                    height: panel.tiles ? width * (panel.coverGrid ? 1 : 0.66) : 44
                                                    radius: entry.artist ? 22 : 6; source: entry.modelData.thumbnail || ""
                                                    Duration { anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: 2; text: entry.video ? entry.modelData.duration || "" : "" }
                                                }
                                                Column {
                                                    x: panel.tiles ? 0 : art.x + art.width + 12
                                                    y: panel.tiles ? art.height + 7 : 12
                                                    width: panel.tiles ? parent.width : parent.width - x - (entry.video || entry.artist ? 28 : 63)
                                                    spacing: 5
                                                    Caption { width: parent.width; text: entry.modelData.title || ""; font.pixelSize: panel.coverGrid ? 14 : panel.tiles || entry.video ? 11 : 12; font.weight: Font.DemiBold }
                                                    Caption { width: parent.width; text: root.credit(entry.modelData); font.pixelSize: panel.coverGrid ? 12 : 10; color: "#b8b9b2" }
                                                    Caption {
                                                        width: parent.width; visible: panel.tiles
                                                        text: entry.modelData.itemCount || [entry.modelData.releaseType || "", entry.modelData.year || ""].filter(Boolean).join(" • ")
                                                        font.pixelSize: 10; color: "#999e94"
                                                    }
                                                }
                                                Caption {
                                                    anchors.right: parent.right; anchors.rightMargin: 29; anchors.verticalCenter: parent.verticalCenter
                                                    visible: !panel.tiles && !entry.video && !entry.artist
                                                    text: entry.modelData.duration || ""; font.pixelSize: 10; color: "#c4c5bd"
                                                }
                                            }
                                        }
                                        Options { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; visible: !panel.tiles; media: entry.modelData }
                                        // Albums get a room; their context menu gets a side door.
                                        TapHandler { acceptedButtons: Qt.RightButton; onTapped: entryMenu.popup(entry, 0, entry.height) }
                                        MediaMenu {
                                            id: entryMenu; media: entry.modelData
                                            onAlbumRequested: album => root.albumRequested(album)
                                            onArtistRequested: artist => root.artistRequested(artist)
                                            onPlaylistRequested: playlist => root.playlistRequested(playlist)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Column {
        anchors.centerIn: parent; width: Math.min(parent.width - 48, 420); spacing: 14
        opacity: root.resultCount === 0 ? 1 : 0; visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: Motion.normal; easing.type: Motion.enter } }
        BusyIndicator { anchors.horizontalCenter: parent.horizontalCenter; running: root.loading; visible: running }
        Caption {
            width: parent.width; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.Wrap
            text: root.loading ? qsTr("Searching YouTube Music…") : root.errorMessage
                || (root.query.trim() ? (OrchardNetwork.offline ? qsTr("Nothing downloaded matches “%1”").arg(root.query.trim()) : qsTr("No results for “%1”").arg(root.query.trim()))
                    : OrchardNetwork.offline ? qsTr("Search the songs and playlists saved on this computer")
                    : qsTr("Search for songs, videos, albums, artists, and playlists"))
            color: root.errorMessage ? "#e6a197" : "#b8b9b2"
        }
        Button { anchors.horizontalCenter: parent.horizontalCenter; text: qsTr("Try again"); visible: !root.loading && Boolean(root.errorMessage); onClicked: root.retryRequested() }
    }
}
