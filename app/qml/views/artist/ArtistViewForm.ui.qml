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
import QtQuick.Controls

// Artist page layout. Data and input handling live in ArtistView.qml.
Item {
    id: root

    property bool sharedBackground: false
    property bool loading: false
    property string errorMessage: ""
    property var detail: ({
        title: "Daft Punk",
        subtitle: "12.4M subscribers",
        description: "French electronic music duo formed in Paris in 1993. They helped bring house and techno into the mainstream."
    })
    property var tracks: [
        { id: "t1", title: "One More Time", artist: "Daft Punk", album: "Discovery", duration: "5:20" },
        { id: "t2", title: "Get Lucky", artist: "Daft Punk", album: "Random Access Memories", duration: "6:09" },
        { id: "t3", title: "Around the World", artist: "Daft Punk", album: "Homework", duration: "3:49" }
    ]
    property var orderedSections: [
        { title: "Albums", items: [
            { type: "album", title: "Discovery", subtitle: "2001" },
            { type: "album", title: "Homework", subtitle: "1997" }
        ] }
    ]
    property var latestRelease: ({ type: "album", title: "Random Access Memories", subtitle: "Album · 2013" })
    property string latestCaption: ""
    // Section shown as a full grid by "See all"; null shows the artist page.
    property var expandedSection: null
    property string expandedKind: "albums"
    property int expandedAlbumWidth: 184
    property bool collectionPlaying: false
    property string browseIdentity: ""
    // Bottom padding so the last row clears the player pill.
    property real playerInset: 0

    property color accentColor: "#7fbe90"
    property color accentSoftColor: "#96caa4"
    property color deepColor: "#0f1512"
    property color inkColor: "#080c0a"
    property color accentInkColor: "#09100b"

    readonly property bool compact: width < 760
    // Latest release sits beside Popular once there is room for both.
    readonly property bool hasLatest: !!latestRelease
    readonly property bool hasPopular: tracks.length > 0
    readonly property bool splitTop: hasLatest && width >= 980
    readonly property int pageInset: width < 600 ? 14 : 24
    // Wide artist artwork runs edge to edge.
    readonly property bool heroBleeds: hero.bleeding

    property alias scroll: scroll
    property alias hero: hero
    property alias backButton: backButton
    property alias latestCard: latestCard
    property alias popularList: popularList
    property alias shelfList: shelfList
    property alias moreButton: moreButton
    property alias aboutText: aboutText
    property alias sectionGrid: sectionGrid
    property alias stickyBar: stickyBar
    property alias pageLoad: pageLoad

    focus: visible

    Rectangle {
        anchors.fill: parent
        visible: !root.sharedBackground

        gradient: Gradient {
            GradientStop { position: 0; color: root.deepColor }
            GradientStop { position: 1; color: root.inkColor }
        }
    }

    DetailFlickable {
        id: scroll

        anchors.fill: parent
        visible: !root.expandedSection
        contentWidth: width
        contentHeight: content.height + 28
        boundsBehavior: Flickable.StopAtBounds

        Column {
            id: content

            x: root.pageInset
            y: root.heroBleeds ? 0 : 14
            width: scroll.width - root.pageInset * 2
            spacing: 14

            // Holds the back button's slot unless the hero is sliding under it.
            Item {
                width: 1
                height: backButton.height
                visible: !root.heroBleeds
            }

            ArtistHero {
                id: hero

                x: root.heroBleeds ? -root.pageInset : 0
                width: root.heroBleeds ? scroll.width : parent.width
                fullBleed: !!root.detail.heroArtwork
                sideInset: root.heroBleeds ? root.pageInset : 0
                compact: root.compact
                title: root.detail.title || qsTr("Artist")
                subtitle: root.detail.subtitle || ""
                // Wide art comes from TheAudioDB or the YouTube Music artist header.
                banner: root.detail.heroArtwork || root.detail.thumbnail || ""
                portrait: root.detail.thumbnail || ""
                scrollY: scroll.contentY
                actionsEnabled: root.tracks.length > 0 && !root.loading
                playing: root.collectionPlaying
                shareEnabled: !!root.browseIdentity
                accentColor: root.accentColor
                accentSoftColor: root.accentSoftColor
                accentInkColor: root.accentInkColor
                inkColor: root.inkColor
            }

            Item {
                width: 1
                height: 10
            }

            Item {
                id: topRow
                width: parent.width
                // Bind to data: a child's visible reads false while this parent is hidden.
                height: root.splitTop ? Math.max(latest.height, root.hasPopular ? popular.height : 0)
                    : (root.hasPopular ? popular.y + popular.height : root.hasLatest ? latest.height : 0)
                visible: root.hasLatest || root.hasPopular

                Column {
                    id: latest

                    width: root.splitTop ? 232 : parent.width
                    spacing: 0
                    visible: root.hasLatest

                    Text {
                        bottomPadding: 10
                        text: qsTr("Latest release")
                        color: "#f2efe7"
                        font.family: "Inter"
                        font.pixelSize: 20
                        font.weight: Font.DemiBold
                    }

                    MediaCard {
                        id: latestCard
                        media: root.latestRelease || ({})
                        presentation: root.splitTop ? "album" : "compact"
                        preferredWidth: 232
                        caption: root.latestCaption
                        animatedArtist: root.detail.title || ""
                        navigable: media.type === "album" || media.type === "playlist"
                    }
                }

                Column {
                    id: popular

                    x: root.splitTop ? latest.width + 32 : 0
                    y: root.splitTop ? 0 : root.hasLatest ? latest.height + 24 : 0
                    width: parent.width - x
                    spacing: 0
                    visible: root.hasPopular

                    Text {
                        x: 12
                        bottomPadding: 10
                        text: qsTr("Popular")
                        color: "#f2efe7"
                        font.family: "Inter"
                        font.pixelSize: 20
                        font.weight: Font.DemiBold
                    }

                    // The logic layer swaps in a delegate that plays rows and opens menus.
                    Repeater {
                        id: popularList
                        model: root.tracks

                        delegate: CollectionTrackRow {
                            required property var modelData
                            required property int index

                            width: parent.width
                            track: modelData
                            trackIndex: index
                            showArtwork: true
                            showAlbum: !root.compact
                            fallbackArtist: root.detail.title || ""
                            accentColor: root.accentColor
                            accentSoftColor: root.accentSoftColor
                        }
                    }
                }
            }

            // The logic layer swaps in a delegate that routes shelf signals.
            Repeater {
                id: shelfList
                model: root.orderedSections

                delegate: Item {
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
                        animatedArtist: root.detail.title || ""
                    }
                }
            }

            // The Wikipedia paragraph everyone skims before hitting play.
            Rectangle {
                width: parent.width
                height: aboutColumn.height + 48
                visible: !!root.detail.description
                radius: 20
                color: Qt.rgba(root.inkColor.r, root.inkColor.g, root.inkColor.b, 0.55)
                border.color: "#12ffffff"

                Column {
                    id: aboutColumn

                    x: 24
                    y: 24
                    width: parent.width - 48
                    spacing: 12

                    Text {
                        text: qsTr("About")
                        color: "#f2efe7"
                        font.family: "Inter"
                        font.pixelSize: 20
                        font.weight: Font.DemiBold
                    }

                    Text {
                        id: aboutText

                        property bool expanded: false

                        width: Math.min(parent.width, 820)
                        text: root.detail.description || ""
                        color: "#bcc3bc"
                        font.family: "Inter"
                        font.pixelSize: 14
                        lineHeight: 1.5
                        wrapMode: Text.Wrap
                        textFormat: Text.PlainText
                        maximumLineCount: expanded ? 400 : 5
                        elide: Text.ElideRight
                    }

                    Button {
                        id: moreButton

                        visible: aboutText.truncated || aboutText.expanded
                        leftPadding: 0
                        text: aboutText.expanded ? qsTr("Show less") : qsTr("Read more")

                        background: null

                        contentItem: Text {
                            text: moreButton.text
                            color: moreButton.hovered ? "white" : root.accentSoftColor
                            font.family: "Inter"
                            font.pixelSize: 13
                            font.weight: Font.DemiBold
                            font.underline: moreButton.activeFocus
                        }
                    }
                }
            }

            Text {
                width: parent.width
                height: 72
                visible: !root.loading && !root.errorMessage && !root.tracks.length && !root.orderedSections.length
                text: qsTr("No music available for this artist. (??????)")
                color: root.accentSoftColor
                verticalAlignment: Text.AlignVCenter
                horizontalAlignment: Text.AlignHCenter
                font.family: "Inter"
                font.pixelSize: 13
            }
        }

        // Floats above the content so a bled hero can slide underneath it.
        Button {
            id: backButton

            x: root.pageInset
            y: 14
            width: 84
            height: 34
            text: qsTr("Back")

            background: Rectangle {
                radius: 17
                // Dark chip keeps the label legible over fanart.
                color: backButton.hovered ? "#30ffffff" : root.heroBleeds ? "#40000000" : "transparent"
                border.color: backButton.activeFocus ? root.accentSoftColor : "transparent"
            }

            contentItem: Row {
                spacing: 8

                LucideIcon {
                    name: "chevron-left"
                    width: 16
                    height: 16
                    anchors.verticalCenter: parent.verticalCenter
                    color: root.heroBleeds ? "#f2f3ef" : root.accentSoftColor
                }

                Text {
                    text: backButton.text
                    color: root.heroBleeds ? "#f2f3ef" : root.accentSoftColor
                    font.family: "Inter"
                    font.pixelSize: 12
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
        }
    }

    // "See all" grid; the artist page keeps its scroll position underneath.
    ArtistSectionGrid {
        id: sectionGrid

        anchors.fill: parent
        anchors.leftMargin: root.pageInset
        anchors.rightMargin: root.pageInset
        visible: !!root.expandedSection
        section: root.expandedSection || ({})
        kind: root.expandedKind
        albumWidth: root.expandedAlbumWidth
        playerInset: root.playerInset
        artistTitle: root.detail.title || ""
        accentSoftColor: root.accentSoftColor
    }

    CollectionStickyBar {
        id: stickyBar
        x: root.pageInset
        y: 8
        width: root.width - root.pageInset * 2
        shown: !root.loading && !root.expandedSection && scroll.contentY > content.y + hero.y + hero.height - 40
        title: root.detail.title || qsTr("Artist")
        artwork: root.detail.thumbnail || ""
        artworkRadius: 18
        playing: root.collectionPlaying
        actionsEnabled: root.tracks.length > 0
        accentColor: root.accentColor
        accentInkColor: root.accentInkColor
        inkColor: root.inkColor
    }

    PageLoadState {
        id: pageLoad
        anchors.fill: parent
        loading: root.loading
        errorMessage: root.errorMessage
        accentColor: root.accentColor
    }
}
