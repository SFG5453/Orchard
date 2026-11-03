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
    // Top Songs as shown; the logic layer trims tracks to what fits the grid.
    property var popularTracks: tracks
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
    // The page runs under the top bar and from the sidebar edge; content keeps clear of both.
    property real topInset: 0
    property real edgeInset: 0

    // Backdrop video for the hero; the logic layer picks and resolves it.
    property bool heroVideoWanted: false
    // {label, value} pairs for the hero's credit row.
    property var heroCredits: []
    property url heroVideoSource: ""
    property string heroVideoTitle: ""
    property bool heroVideoPlaying: true
    property color accentColor: OrchardAppearance.accent
    property color accentSoftColor: OrchardAppearance.accent
    property color deepColor: "#0f1512"
    property color inkColor: "#080c0a"
    property color accentInkColor: "#09100b"

    readonly property bool compact: width < 760
    // Latest release sits beside Popular once there is room for both.
    readonly property bool hasLatest: !!latestRelease
    readonly property bool hasPopular: tracks.length > 0
    readonly property bool splitTop: hasLatest && width >= 980
    readonly property int pageInset: edgeInset + (width < 600 ? 14 : 24)
    readonly property int songColumns: width >= 1500 ? 3 : width >= 1000 ? 2 : 1

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
            width: scroll.width - root.pageInset * 2
            spacing: 14

            ArtistHero {
                id: hero

                x: -root.pageInset
                width: scroll.width
                compact: root.compact
                topInset: root.topInset
                title: root.detail.title || qsTr("Artist")
                // Wide art comes from Apple, TheAudioDB or the YouTube Music artist header.
                banner: root.detail.heroArtwork || root.detail.thumbnail || ""
                portrait: root.detail.thumbnail || ""
                logo: root.detail.logo || ""
                poster: root.heroVideoWanted ? root.detail.heroPoster || "" : ""
                scrollY: scroll.contentY
                videoWanted: root.heroVideoWanted
                videoSource: root.heroVideoSource
                videoTitle: root.heroVideoTitle
                videoPlaying: root.heroVideoPlaying
                actionsEnabled: root.tracks.length > 0 && !root.loading
                playing: root.collectionPlaying
                shareEnabled: !!root.browseIdentity
                viewportHeight: scroll.height
                accentSoftColor: root.accentSoftColor
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

                    width: root.splitTop ? 200 : parent.width
                    spacing: 0
                    visible: root.hasLatest

                    Text {
                        bottomPadding: 12
                        text: qsTr("Latest Release")
                        color: "#f2efe7"
                        font.family: "Inter"
                        font.pixelSize: 19
                        font.weight: Font.Bold
                    }

                    MediaCard {
                        id: latestCard
                        media: root.latestRelease || ({})
                        presentation: root.splitTop ? "album" : "compact"
                        preferredWidth: 200
                        caption: root.latestCaption
                        animatedArtist: root.detail.title || ""
                        navigable: media.type === "album" || media.type === "playlist"
                    }
                }

                Column {
                    id: popular

                    x: root.splitTop ? latest.width + 40 : 0
                    y: root.splitTop ? 0 : root.hasLatest ? latest.height + 24 : 0
                    width: parent.width - x
                    spacing: 0
                    visible: root.hasPopular

                    Text {
                        bottomPadding: 6
                        text: qsTr("Top Songs")
                        color: "#f2efe7"
                        font.family: "Inter"
                        font.pixelSize: 19
                        font.weight: Font.Bold
                    }

                    // Columns fill top to bottom, as on music.apple.com.
                    Grid {
                        id: songGrid

                        width: parent.width
                        columns: root.songColumns
                        rows: Math.ceil(root.popularTracks.length / root.songColumns)
                        flow: Grid.TopToBottom
                        columnSpacing: 28

                        // The logic layer swaps in a delegate that plays rows and opens menus.
                        Repeater {
                            id: popularList
                            model: root.popularTracks

                            delegate: ArtistSongCell {
                                required property var modelData
                                required property int index

                                width: (songGrid.width - songGrid.columnSpacing * (root.songColumns - 1)) / root.songColumns
                                track: modelData
                                trackIndex: index
                                fallbackArtist: root.detail.title || ""
                                accentSoftColor: root.accentSoftColor
                            }
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
                visible: !!root.detail.description || root.heroCredits.length > 0
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
                        readonly property bool html: root.detail.source === "apple_music"
                            || /<\/?(?:a|b|br|div|em|i|li|ol|p|span|strong|u|ul)\b/i.test(root.detail.description || "")

                        width: Math.min(parent.width, 820)
                        text: html ? String(root.detail.description || "").replace(/\r\n?|\n/g, "<br>")
                                   : root.detail.description || ""
                        color: "#bcc3bc"
                        font.family: "Inter"
                        font.pixelSize: 14
                        lineHeight: 1.5
                        wrapMode: Text.Wrap
                        textFormat: html ? Text.StyledText : Text.PlainText
                        linkColor: "#f1f3ee"
                        maximumLineCount: expanded ? 400 : 5
                        elide: Text.ElideRight

                        HoverHandler {
                            cursorShape: aboutText.hoveredLink ? Qt.PointingHandCursor : Qt.ArrowCursor
                        }
                    }

                    Row {
                        spacing: 40
                        visible: root.heroCredits.length > 0

                        Repeater {
                            model: root.heroCredits

                            delegate: Column {
                                required property var modelData

                                spacing: 6

                                Text {
                                    text: modelData.label
                                    color: "#99ffffff"
                                    font.family: "Inter"
                                    font.pixelSize: 10
                                    font.weight: Font.DemiBold
                                    font.letterSpacing: 2.2
                                    font.capitalization: Font.AllUppercase
                                }
                                Text {
                                    text: modelData.value
                                    color: "#f2efe7"
                                    font.family: "Inter"
                                    font.pixelSize: 14
                                    font.weight: Font.Medium
                                }
                            }
                        }
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

            // Sits in the top bar's row, left of the search field.
            x: root.pageInset
            y: Math.max(8, root.topInset - 49)
            width: 36
            height: 36
            text: qsTr("Back")
            display: AbstractButton.IconOnly
            Accessible.name: qsTr("Back")

            background: Rectangle {
                radius: width / 2
                color: backButton.hovered ? "#40ffffff" : "#26ffffff"
                border.color: backButton.activeFocus ? root.accentSoftColor : "transparent"
            }

            contentItem: Item {
                LucideIcon {
                    anchors.centerIn: parent
                    name: "chevron-left"
                    width: 18
                    height: 18
                    color: "#f2f3ef"
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
        anchors.topMargin: root.topInset
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
        y: root.topInset + 8
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
        anchors.topMargin: root.topInset
        loading: root.loading
        errorMessage: root.errorMessage
        accentColor: root.accentColor
    }
}
