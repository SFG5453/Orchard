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
import QtQuick
import QtQuick.Controls

// "See all" card grid for one artist shelf.
GridView {
    id: grid

    property var section: ({ title: "Albums", items: [] })
    // "albums", "singles", "videos", "related" or "other"; videos get wide cards.
    property string kind: "albums"
    property int albumWidth: 184
    property real playerInset: 0
    property string artistTitle: ""
    property color accentSoftColor: "#96caa4"
    readonly property string presentation: kind === "videos" ? "video" : "album"
    readonly property int cardWidth: presentation === "video" ? 258 : albumWidth

    clip: true
    boundsBehavior: Flickable.StopAtBounds
    model: section.items || []
    cacheBuffer: 400
    // Stretch cards to fill each row instead of leaving a ragged right edge.
    cellWidth: width / Math.max(1, Math.floor(width / (cardWidth + 14)))
    cellHeight: (presentation === "video" ? Math.round((cellWidth - 14) * 9 / 16) + 57 : cellWidth - 14 + 52) + 18
    bottomMargin: grid.playerInset + 14

    ScrollBar.vertical: ScrollBar {
        width: 5
        bottomPadding: grid.playerInset
    }

    header: Item {
        property alias backButton: gridBack
        width: grid.width
        height: 110

        Button {
            id: gridBack

            y: 14
            width: 84
            height: 34
            text: qsTr("Back")

            background: Rectangle {
                radius: 17
                color: gridBack.hovered ? "#30ffffff" : "transparent"
                border.color: gridBack.activeFocus ? grid.accentSoftColor : "transparent"
            }

            contentItem: Row {
                spacing: 8

                LucideIcon {
                    name: "chevron-left"
                    width: 16
                    height: 16
                    anchors.verticalCenter: parent.verticalCenter
                    color: grid.accentSoftColor
                }

                Text {
                    text: gridBack.text
                    color: grid.accentSoftColor
                    font.family: "Inter"
                    font.pixelSize: 12
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
        }

        Row {
            y: 62
            spacing: 10

            Text {
                id: gridTitle

                text: grid.section.title || ""
                color: "#f2efe7"
                font.family: "Inter"
                font.pixelSize: 26
                font.weight: Font.Bold
            }

            Text {
                anchors.baseline: gridTitle.baseline
                text: grid.artistTitle
                color: "#a4a9ad"
                font.family: "Inter"
                font.pixelSize: 14
            }
        }
    }

    // The logic layer swaps in a delegate that opens releases and videos.
    delegate: MediaCard {
        required property var modelData

        width: grid.cellWidth - 14
        media: modelData
        presentation: grid.presentation
        animatedArtist: grid.artistTitle
        navigable: media.type === "album" || media.type === "artist" || media.type === "playlist"
    }
}
