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

import QtQuick
import QtQuick.Controls
import QtQuick.Effects
import ".."
import "../home"

// Album and playlist side panel: cover, titles, actions and blurb in one column.
Item {
    id: root

    property string kind
    property string title
    property string byline
    property bool bylineInteractive: false
    property string year
    property var meta: []
    property string description
    property string artwork
    property url animatedArtwork
    // Albums behind a four-cover playlist collage; see CollageArtwork.
    property var collageAlbums: []
    property bool explicit: false
    // Qobuz quality for albums it can stream; see QualityBadge.
    property var streamQuality: ({})
    property bool compact: false
    property bool actionsEnabled: true
    property bool playing: false
    property bool searchable: false
    // "none", "partial", "busy" or "done" for the songs on this page.
    property bool downloadable: false
    property string downloadState: "none"
    property bool downloadEnabled: true
    property string shareLabel
    property bool shareEnabled: true

    property color accentColor: "#7fbe90"
    property color accentSoftColor: "#96caa4"
    property color accentInkColor: "#09100b"
    property color inkColor: "#080c0a"

    readonly property int coverRadius: 6
    readonly property real coverSize: compact ? Math.min(width, 240) : width

    signal bylineClicked(Item anchor, real x, real y)
    signal playClicked
    signal shuffleClicked
    signal shareClicked
    signal shareOpenRequested
    signal searchClicked
    signal downloadClicked
    signal removeDownloadsClicked

    function tint(color, alpha) { return Qt.rgba(color.r, color.g, color.b, alpha); }

    implicitHeight: card.height

    component GhostButton: Button {
        id: ghost
        property string glyph
        property color glyphColor: "#eef0ec"
        width: 40
        height: 40
        padding: 11
        opacity: enabled ? 1 : 0.4
        ToolTip.visible: hovered && ToolTip.text.length > 0
        ToolTip.delay: 400
        background: Rectangle {
            radius: 6
            color: ghost.hovered ? "#1fffffff" : "transparent"
            border.color: ghost.activeFocus ? root.accentSoftColor : "#33ffffff"
            Behavior on color { ColorAnimation { duration: 120 } }
        }
        contentItem: LucideIcon { name: ghost.glyph; color: ghost.glyphColor }
    }

    Column {
        id: card
        width: root.width
        spacing: 0

        Item {
            id: cover
            width: root.coverSize
            height: root.coverSize
            x: root.compact ? (card.width - width) / 2 : 0

            Rectangle {
                id: coverShadowSource
                anchors.fill: parent
                radius: root.coverRadius
                color: "black"
                visible: false
            }
            MultiEffect {
                anchors.fill: parent
                source: coverShadowSource
                autoPaddingEnabled: true
                shadowEnabled: true
                shadowBlur: 1
                shadowOpacity: 0.45
                shadowVerticalOffset: 10
                shadowColor: "black"
            }
            RoundedArtwork {
                anchors.fill: parent
                radius: root.coverRadius
                source: root.artwork
            }
            Loader {
                anchors.fill: parent
                active: OrchardAppearance.animatedArtworkEnabled
                        && root.animatedArtwork.toString().length > 0 && root.visible
                sourceComponent: AnimatedArtwork {
                    radius: root.coverRadius
                    source: root.animatedArtwork
                    playing: root.visible
                }
            }
            Loader {
                anchors.fill: parent
                // Opt-in separately: this one runs up to four videos at once.
                active: OrchardAppearance.animatedArtworkEnabled
                        && OrchardAppearance.animatedCollageEnabled
                        && root.collageAlbums.length === 4 && root.visible
                sourceComponent: CollageArtwork {
                    radius: root.coverRadius
                    albums: root.collageAlbums
                    playing: root.visible
                }
            }
            LucideIcon {
                anchors.centerIn: parent
                width: 56
                height: 56
                name: "music-2"
                color: root.tint(root.accentSoftColor, 0.7)
                visible: !root.artwork
            }
            Rectangle {
                anchors.fill: parent
                radius: root.coverRadius
                color: "transparent"
                border.color: "#1affffff"
            }
        }

        Item { width: 1; height: 16 }

        Row {
            spacing: 6
            Text {
                text: [root.kind, root.year].filter(Boolean).join("  ·  ")
                color: "#9aa09a"
                font.family: "Inter"
                font.pixelSize: 12
            }
            ExplicitBadge {
                visible: root.explicit
                anchors.verticalCenter: parent.verticalCenter
            }
            QualityBadge {
                anchors.verticalCenter: parent.verticalCenter
                quality: root.streamQuality
                color: root.tint(root.accentSoftColor, 0.18)
                textColor: root.accentSoftColor
            }
        }
        Item { width: 1; height: 6 }
        Text {
            width: parent.width
            text: root.title
            color: "#f6f4ef"
            font.family: "Inter"
            font.pixelSize: root.title.length > 40 ? 22 : 28
            font.weight: Font.Bold
            font.letterSpacing: -0.5
            lineHeight: 1.0
            wrapMode: Text.Wrap
            maximumLineCount: 3
            elide: Text.ElideRight
        }
        Item { width: 1; height: bylineText.visible ? 10 : 0 }
        Text {
            id: bylineText
            width: Math.min(implicitWidth, parent.width)
            visible: text.length > 0
            text: root.byline
            color: bylineMouse.containsMouse ? "white" : "#f6f4ef"
            font.family: "Inter"
            font.pixelSize: 14
            font.weight: Font.DemiBold
            font.underline: bylineMouse.containsMouse
            elide: Text.ElideRight
            MouseArea {
                id: bylineMouse
                anchors.fill: parent
                enabled: root.bylineInteractive
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: function(mouse) { root.bylineClicked(bylineText, mouse.x, mouse.y); }
            }
        }
        Item { width: 1; height: 4 }
        Text {
            width: parent.width
            visible: text.length > 0
            text: root.meta.filter(Boolean).join("  ·  ")
            color: "#9aa09a"
            font.family: "Inter"
            font.pixelSize: 12
            elide: Text.ElideRight
        }
        Item { width: 1; height: 20 }

        Row {
            spacing: 8

            Button {
                id: playButton
                width: playRow.implicitWidth + 36
                height: 40
                enabled: root.actionsEnabled
                opacity: enabled ? 1 : 0.4
                Accessible.name: root.playing ? qsTr("Pause") : qsTr("Play %1").arg(root.kind.toLowerCase())
                onClicked: root.playClicked()
                scale: down ? 0.97 : 1
                Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                background: Rectangle {
                    radius: 6
                    color: playButton.hovered ? "white" : "#f1efea"
                    border.color: playButton.activeFocus ? root.accentSoftColor : "transparent"
                    border.width: 2
                }
                contentItem: Item {
                    Row {
                        id: playRow
                        anchors.centerIn: parent
                        spacing: 8
                        LucideIcon {
                            width: 16
                            height: 16
                            anchors.verticalCenter: parent.verticalCenter
                            name: root.playing ? "pause" : "play"
                            color: "#121212"
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: root.playing ? qsTr("Pause") : qsTr("Play")
                            color: "#121212"
                            font.family: "Inter"
                            font.pixelSize: 13
                            font.weight: Font.DemiBold
                        }
                    }
                }
            }
            GhostButton {
                glyph: "shuffle"
                enabled: root.actionsEnabled
                Accessible.name: qsTr("Shuffle")
                ToolTip.text: qsTr("Shuffle")
                onClicked: root.shuffleClicked()
            }
            GhostButton {
                id: downloadButton
                visible: root.downloadable
                glyph: root.downloadState === "done" ? "circle-arrow-down" : "download"
                glyphColor: root.downloadState === "done" ? root.accentSoftColor : "#eef0ec"
                enabled: root.downloadEnabled
                opacity: !enabled ? 0.4 : root.downloadState === "busy" ? 0.7 : 1
                Accessible.name: ToolTip.text
                ToolTip.text: root.downloadState === "done" ? qsTr("Downloaded. Click for options.")
                    : root.downloadState === "busy" ? qsTr("Downloading…")
                    : root.downloadState === "partial" ? qsTr("Download the rest for offline listening")
                    : qsTr("Download for offline listening")
                onClicked: {
                    if (root.downloadState === "done")
                        downloadMenu.popup(downloadButton, 0, downloadButton.height);
                    else
                        root.downloadClicked();
                }
            }
            // One click to rule them all, one link to find them.
            GhostButton {
                glyph: "share-2"
                enabled: root.shareEnabled
                Accessible.name: root.shareLabel
                ToolTip.text: qsTr("%1 (right-click to open)").arg(root.shareLabel)
                onClicked: root.shareClicked()
                TapHandler {
                    acceptedButtons: Qt.RightButton
                    onTapped: root.shareOpenRequested()
                }
            }
            GhostButton {
                visible: root.searchable
                glyph: "search"
                enabled: root.actionsEnabled
                Accessible.name: qsTr("Find in %1").arg(root.kind.toLowerCase())
                ToolTip.text: qsTr("Find in %1 (or just start typing)").arg(root.kind.toLowerCase())
                onClicked: root.searchClicked()
            }
        }

        Item { width: 1; height: descriptionText.visible ? 20 : 0 }
        Text {
            id: descriptionText
            property bool expanded: false
            width: parent.width
            visible: text.length > 0
            text: root.description
            color: "#9aa09a"
            font.family: "Inter"
            font.pixelSize: 12
            lineHeight: 1.35
            wrapMode: Text.Wrap
            maximumLineCount: expanded ? 40 : 4
            elide: Text.ElideRight
        }
        Item { width: 1; height: readMore.visible ? 14 : 0 }
        Text {
            id: readMore
            visible: descriptionText.truncated || descriptionText.expanded
            text: descriptionText.expanded ? qsTr("Show less") : qsTr("Read more")
            color: readMoreMouse.containsMouse ? "white" : "#d8dcd6"
            font.family: "Inter"
            font.pixelSize: 12
            font.weight: Font.DemiBold
            MouseArea {
                id: readMoreMouse
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: descriptionText.expanded = !descriptionText.expanded
            }
        }
    }

    Menu {
        id: downloadMenu
        width: 210
        padding: 5
        background: Rectangle { color: "#242c27"; radius: 10; border.color: "#455348" }

        MenuItem {
            id: removeItem
            text: qsTr("Remove downloads")
            implicitHeight: 36
            onTriggered: root.removeDownloadsClicked()
            background: Rectangle { radius: 6; color: removeItem.highlighted ? "#435247" : "#00435247" }
            contentItem: Text {
                text: removeItem.text
                color: "#f2f0eb"
                font.family: "Inter"
                font.pixelSize: 13
                verticalAlignment: Text.AlignVCenter
            }
        }
    }
}
