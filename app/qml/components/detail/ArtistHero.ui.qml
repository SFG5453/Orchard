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
import "../home"
import QtQuick
import QtQuick.Controls
import QtQuick.Effects

// Artist header: edge-to-edge motion video or wide art, name and actions centered over its lower edge.
// The picture's bottom melts through a blurred copy of itself into the page tint.
Item {
    id: root

    property string title: "Artist"
    property string banner: ""
    property string portrait: ""
    // Apple's name artwork; replaces the title text when present.
    property string logo: ""
    // First frame of the motion video, shown until it plays.
    property string poster: ""
    property bool compact: false
    property bool actionsEnabled: true
    property bool playing: false
    property bool shareEnabled: true
    // Page scroll offset, drives the parallax.
    property real scrollY: 0
    // Muted backdrop video. videoWanted reserves the wide layout before frames arrive.
    property url videoSource: ""
    property bool videoWanted: false
    property bool videoPlaying: true
    // Names the Watch button; empty hides it.
    property string videoTitle: ""
    // Height the top bar covers; the name stays clear of it.
    property real topInset: 0
    // Visible page height; the header leaves room for Top Songs above the player.
    property real viewportHeight: 1080

    property color accentSoftColor: OrchardAppearance.accent

    readonly property bool masked: GraphicsInfo.api !== GraphicsInfo.Software
    // Anything wider than 4:3 is a real banner; squarer images would lose the face to cropping.
    readonly property bool bannerMode: bannerProbe.status === Image.Ready
                                       && bannerProbe.implicitWidth > bannerProbe.implicitHeight * 1.34
    readonly property bool wideMode: videoWanted || poster.length > 0 || bannerMode
    // Video heroes show the whole frame, up to a full viewport. Narrow windows crop the sides.
    // Other wide art uses about 42% of the page width, capped so the first rows of songs stay in view.
    readonly property real mediaHeight: videoWanted
        ? Math.round(Math.max(compact ? 340 : 440, Math.min(width / root.backdropVideo.aspect, viewportHeight)))
        : wideMode
        ? Math.round(Math.max(compact ? 340 : 440, Math.min(width * 0.42 + topInset * 0.5, viewportHeight * 0.62)))
        : (compact ? 300 : 380) + topInset
    // Share of the lower picture the blur and fade use; video keeps more of its frame sharp.
    readonly property real blendDepth: videoWanted ? 0.55 : 1
    readonly property int nameSize: compact ? 32 : title.length > 24 ? 38 : 48

    property alias playButton: playButton
    property alias shuffleButton: shuffleButton
    property alias shareButton: shareButton
    property alias backdropVideo: backdropVideo
    property alias watchButton: watchButton

    implicitHeight: mediaHeight + 4

    Image {
        id: bannerProbe
        source: root.banner
        asynchronous: true
        visible: false
    }

    // Whole header fades to transparent at the bottom so it melts into the shell's ambient colors.
    Rectangle {
        id: stageFade
        width: root.width
        height: root.mediaHeight
        visible: false
        layer.enabled: root.masked
        gradient: Gradient {
            GradientStop { position: 0; color: "white" }
            GradientStop { position: 1 - 0.18 * root.blendDepth; color: "white" }
            GradientStop { position: 1 - 0.13 * root.blendDepth; color: "#d7ffffff" }
            GradientStop { position: 1 - 0.07 * root.blendDepth; color: "#68ffffff" }
            GradientStop { position: 1 - 0.02 * root.blendDepth; color: "#09ffffff" }
            GradientStop { position: 1; color: "transparent" }
        }
    }

    Item {
        width: root.width
        height: root.mediaHeight
        layer.enabled: root.masked
        layer.effect: MultiEffect {
            maskEnabled: true
            maskSource: stageFade
            maskThresholdMin: 0.5
            maskSpreadAtMin: 1
        }

        // Capture the visible picture so the blur follows the current video frame.
        ShaderEffectSource {
            id: blurredSource
            width: root.width
            height: root.mediaHeight
            visible: false
            sourceItem: root.wideMode && root.masked ? drift : null
            sourceRect: Qt.rect(0, -drift.y, root.width, root.mediaHeight)
            textureSize: Qt.size(480, Math.max(1, Math.round(root.mediaHeight * 480 / Math.max(1, root.width))))
            live: root.videoPlaying
        }
        // Smoothly reveal the blur over the lower picture, before it fades into the page.
        Rectangle {
            id: blurMask
            width: root.width
            height: root.mediaHeight
            visible: false
            layer.enabled: root.masked
            gradient: Gradient {
                GradientStop { position: 0; color: "transparent" }
                GradientStop { position: 1 - 0.42 * root.blendDepth; color: "transparent" }
                GradientStop { position: 1 - 0.34 * root.blendDepth; color: "#28ffffff" }
                GradientStop { position: 1 - 0.25 * root.blendDepth; color: "#80ffffff" }
                GradientStop { position: 1 - 0.16 * root.blendDepth; color: "#d7ffffff" }
                GradientStop { position: 1 - 0.08 * root.blendDepth; color: "white" }
                GradientStop { position: 1; color: "white" }
            }
        }

        Item {
            id: media
            width: root.width
            height: root.mediaHeight
            clip: true

            // No wide art, or none loaded yet: the portrait blurred at thumbnail size, then upscaled for a cheap glow.
            Item {
                width: 24
                height: 24
                visible: !root.wideMode || (stillImage.status !== Image.Ready && !backdropVideo.live)
                layer.enabled: visible
                layer.smooth: true
                transform: Scale { xScale: media.width / 24; yScale: media.height / 24 }

                Image {
                    id: wash
                    anchors.fill: parent
                    source: root.portrait
                    fillMode: Image.Stretch
                    sourceSize.width: 32
                    asynchronous: true
                    visible: false
                }
                MultiEffect {
                    anchors.fill: parent
                    source: wash
                    blurEnabled: true
                    blurMax: 16
                    blur: 1
                    saturation: 0.4
                }
            }

            Item {
                id: drift
                width: parent.width
                // Oversized so the parallax never exposes the edge; video keeps its full frame and skips it.
                height: root.videoWanted ? parent.height : parent.height * 1.2
                y: root.videoWanted ? 0 : -Math.min(Math.max(root.scrollY, 0) * 0.3, parent.height * 0.2)
                visible: root.wideMode

                Image {
                    id: stillImage
                    anchors.fill: parent
                    source: root.poster.length > 0 ? root.poster : root.banner
                    fillMode: Image.PreserveAspectCrop
                    // Banners keep heads near the top; video frames are composed around the middle.
                    verticalAlignment: root.poster.length > 0 ? Image.AlignVCenter : Image.AlignTop
                    asynchronous: true
                    opacity: status === Image.Ready ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: 320; easing.type: Easing.OutCubic } }
                }
                ArtistBackdropVideo {
                    id: backdropVideo
                    anchors.fill: parent
                    source: root.videoSource
                    playing: root.videoPlaying
                    visibleTop: -drift.y
                    visibleHeight: root.mediaHeight
                    visible: hasVideo
                    opacity: live ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: 900; easing.type: Easing.InOutQuad } }
                }
            }

            // Keeps the top bar legible over bright frames.
            Rectangle {
                width: parent.width
                height: root.topInset + 40
                gradient: Gradient {
                    GradientStop { position: 0; color: "#33000000" }
                    GradientStop { position: 1; color: "transparent" }
                }
            }
        }

        MultiEffect {
            width: root.width
            height: root.mediaHeight
            visible: root.wideMode && root.masked
            source: blurredSource
            autoPaddingEnabled: false
            blurEnabled: true
            blurMax: 64
            blur: 1
            maskEnabled: true
            maskSource: blurMask
            maskThresholdMin: 0.5
            maskSpreadAtMin: 1
        }

        // Darkens the lower picture so the name stays legible on bright frames.
        Rectangle {
            anchors.fill: parent
            gradient: Gradient {
                GradientStop { position: 0; color: "transparent" }
                GradientStop { position: root.wideMode ? 0.55 : 0.3; color: "transparent" }
                GradientStop { position: 1; color: "#26000000" }
            }
        }
    }

    // Portrait layouts center the round artist photo above the name.
    RoundedArtwork {
        id: avatar
        visible: !root.wideMode
        width: root.compact ? 112 : 168
        height: avatar.width
        x: (root.width - avatar.width) / 2
        y: name.y - avatar.height - 22
        radius: avatar.width / 2
        source: root.portrait
    }

    Column {
        id: name
        width: Math.min(root.width - 64, 900)
        x: (root.width - width) / 2
        y: root.mediaHeight - height - (root.compact ? 18 : 30)
        spacing: root.compact ? 14 : 18

        Image {
            id: logoImage
            anchors.horizontalCenter: parent.horizontalCenter
            visible: root.logo.length > 0 && status === Image.Ready
            source: root.logo
            asynchronous: true
            fillMode: Image.PreserveAspectFit
            width: Math.min(parent.width * 0.6, root.compact ? 260 : 420)
            height: visible ? (root.compact ? 64 : 104) : 0
        }
        Text {
            width: parent.width
            visible: !logoImage.visible
            text: root.title
            horizontalAlignment: Text.AlignHCenter
            color: "#ffffff"
            font.family: "Inter"
            font.pixelSize: root.nameSize
            font.weight: Font.Bold
            font.letterSpacing: -root.nameSize * 0.02
            wrapMode: Text.Wrap
            maximumLineCount: 2
            elide: Text.ElideRight
            layer.enabled: root.masked
            layer.effect: MultiEffect {
                shadowEnabled: true
                shadowBlur: 0.8
                shadowOpacity: 0.3
                shadowVerticalOffset: 2
            }
        }

        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: 16

            ArtistHeroGhostButton {
                id: shuffleButton
                anchors.verticalCenter: parent.verticalCenter
                accentSoftColor: root.accentSoftColor
                glyph: "shuffle"
                enabled: root.actionsEnabled
                Accessible.name: qsTr("Shuffle")
                ToolTip.text: qsTr("Shuffle")
            }
            Button {
                id: playButton
                width: root.compact ? 52 : 60
                height: width
                enabled: root.actionsEnabled
                opacity: enabled ? 1 : 0.4
                Accessible.name: root.playing ? qsTr("Pause") : qsTr("Play %1").arg(root.title)
                scale: down ? 0.94 : hovered ? 1.06 : 1
                Behavior on scale { NumberAnimation { duration: 140; easing.type: Easing.OutBack } }
                background: Rectangle {
                    radius: width / 2
                    color: playButton.hovered ? "#ffffff" : "#f4f4f4"
                    border.color: playButton.activeFocus ? root.accentSoftColor : "transparent"
                    border.width: 2
                }
                contentItem: Item {
                    LucideIcon {
                        anchors.centerIn: parent
                        anchors.horizontalCenterOffset: root.playing ? 0 : 2
                        width: 24
                        height: 24
                        name: root.playing ? "pause" : "play"
                        color: "#111111"
                    }
                }
            }
            ArtistHeroGhostButton {
                id: shareButton
                anchors.verticalCenter: parent.verticalCenter
                accentSoftColor: root.accentSoftColor
                glyph: "share-2"
                enabled: root.shareEnabled
                Accessible.name: qsTr("Copy artist link")
                ToolTip.text: qsTr("Copy artist link")
            }
        }
    }

    // Opens the full music video; HomeScene grows it out of this header.
    Button {
        id: watchButton
        visible: root.videoTitle.length > 0 && !root.compact
        x: root.width - width - 32
        y: root.mediaHeight - height - 42
        height: 34
        leftPadding: 14
        rightPadding: 16
        Accessible.name: qsTr("Watch %1").arg(root.videoTitle)
        background: Rectangle {
            radius: height / 2
            color: watchButton.hovered ? "#40ffffff" : "#26ffffff"
            border.color: watchButton.activeFocus ? root.accentSoftColor : "transparent"
            Behavior on color { ColorAnimation { duration: 120 } }
        }
        contentItem: Row {
            spacing: 8

            LucideIcon {
                anchors.verticalCenter: parent.verticalCenter
                width: 14
                height: 14
                name: "play"
                color: "#ffffff"
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: qsTr("Watch “%1”").arg(root.videoTitle)
                color: "#ffffff"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: Font.Medium
            }
        }
    }
}
