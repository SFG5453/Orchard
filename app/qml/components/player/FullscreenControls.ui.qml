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
import QtQuick.Controls
import QtQuick.Effects
import QtQuick.Layouts
import "FullscreenLayout.js" as FullscreenLayout

// Title, progress and controls, stacked under the cover.
ColumnLayout {
    id: controls

    property color accentColor: "#f0eee7"
    property color mixAccentColor: "#f0eee7"
    property color primaryText: "#f7f5f0"
    property color secondaryText: "#c3c6bf"
    property color mutedText: "#8d928a"
    property color inkColor: "#0b0d0a"
    // 0 closed, 1 open. Blocks fade in on their own slice of it.
    property real reveal: 1
    property real infoShift: 0
    // Cover dissolve progress; the two track infos blur through each other on it.
    property real handoff: 0
    property real timeSwap: 1
    property real mixGlow: 0
    property bool mixing: false
    property var currentTrack: ({})
    property var incomingTrack: ({})
    property var mixLetters: ["M", "i", "x", "i", "n", "g"]
    property string pane: "lyrics"
    // Timeline.
    property real shownDuration: 240
    property real shownPosition: 83
    property real shownFraction: 0.35
    property real seekLimit: 240
    property bool seekEnabled: true
    property string positionText: "1:23"
    property string remainingText: "-2:37"
    property string mixDurationText: ""
    property string qualityText: ""
    property real volume: 0.7
    readonly property bool compact: width < 340
    readonly property var transport: FullscreenLayout.transport(width)
    readonly property bool scrubbing: progress.pressed
    readonly property real scrubPosition: progress.shown

    readonly property real stage30: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.3) / 0.7)), 3)
    readonly property real stage40: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.4) / 0.6)), 3)
    readonly property real stage42: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.42) / 0.58)), 3)
    readonly property real stage47: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.47) / 0.53)), 3)
    readonly property real stage52: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.52) / 0.48)), 3)
    readonly property real stage57: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.57) / 0.43)), 3)
    readonly property real stage62: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.62) / 0.38)), 3)
    readonly property real stage60: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.6) / 0.4)), 3)

    property alias currentInfo: currentInfo
    property alias incomingInfo: incomingInfo
    property alias moreButton: moreButton
    property alias likeButton: likeButton
    property alias progress: progress
    property alias shuffleButton: shuffleButton
    property alias previousButton: previousButton
    property alias playButton: playButton
    property alias nextButton: nextButton
    property alias repeatButton: repeatButton
    property alias volumeSlider: volume
    property alias volumeWheel: volumeWheel
    property alias lyricsButton: lyricsButton
    property alias queueButton: queueButton

    spacing: 0

    // Track info slides in after the cover lands and again on every skip.
    RowLayout {
        Layout.fillWidth: true
        spacing: 12
        opacity: controls.stage30 * (1 - controls.infoShift)
        transform: Translate { y: 20 * (1 - controls.stage30) + 14 * controls.infoShift }

        // The outgoing details blur up and away, then the incoming ones blur in from below.
        Item {
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            implicitHeight: Math.max(currentInfo.implicitHeight, incomingInfo.implicitHeight)

            FullscreenTrackInfo {
                id: currentInfo
                width: parent.width
                compact: controls.compact
                track: controls.currentTrack
                primaryText: controls.primaryText
                accentColor: controls.accentColor
                away: Math.max(0, Math.min(1, controls.handoff / 0.55))
                drift: -12
            }

            FullscreenTrackInfo {
                id: incomingInfo
                width: parent.width
                compact: controls.compact
                track: controls.incomingTrack
                primaryText: controls.primaryText
                accentColor: controls.accentColor
                away: controls.handoff > 0 ? 1 - Math.max(0, Math.min(1, (controls.handoff - 0.45) / 0.55)) : 1
                drift: 12
            }
        }

        LikeButton {
            id: likeButton
            Layout.alignment: Qt.AlignVCenter
            implicitWidth: 36
            implicitHeight: 36
            iconSize: 19
            iconColor: controls.secondaryText
            likedColor: controls.accentColor
        }

        FullscreenIconButton {
            id: moreButton
            Layout.alignment: Qt.AlignVCenter
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            secondaryText: controls.secondaryText
            glyph: "ellipsis"
            ToolTip.text: qsTr("More")
        }
    }

    Slider {
        id: progress
        Layout.fillWidth: true
        Layout.minimumWidth: 0
        Layout.topMargin: 18
        Layout.preferredHeight: 22
        opacity: controls.stage40
        transform: Translate { y: 20 * (1 - controls.stage40) }
        from: 0
        readonly property real shown: pressed ? Math.min(value, controls.seekLimit) : value
        to: Math.max(1, controls.shownDuration)
        value: pressed ? value : controls.shownPosition
        enabled: controls.seekEnabled
        live: true
        hoverEnabled: true
        leftPadding: 0
        rightPadding: 0

        background: Item {
            x: progress.leftPadding
            y: progress.topPadding
            width: progress.availableWidth
            height: progress.availableHeight

            Rectangle {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width
                // The bar swells while mixing, same as hovering but with conviction.
                height: controls.mixing ? 10 : progress.hovered || progress.pressed ? 9 : 5
                radius: height / 2
                color: Qt.rgba(controls.primaryText.r, controls.primaryText.g, controls.primaryText.b, 0.18 + 0.1 * controls.mixGlow)
                Behavior on height { NumberAnimation { duration: 420; easing.type: Easing.OutBack; easing.overshoot: 2.4 } }

                MixDotFill {
                    anchors.fill: parent
                    fraction: Math.min(1, progress.pressed ? progress.shown / progress.to : controls.shownFraction)
                    baseColor: controls.mixing || progress.hovered || progress.pressed ? "#ffffff"
                         : Qt.rgba(controls.primaryText.r, controls.primaryText.g, controls.primaryText.b, 0.82)
                    colorFade: 400
                    dotColor: controls.mixAccentColor
                    amount: controls.mixGlow
                    maxBand: 280
                    rows: 3
                    layer.enabled: controls.mixGlow > 0.01
                    layer.effect: MultiEffect {
                        autoPaddingEnabled: true
                        shadowEnabled: true
                        shadowColor: controls.mixAccentColor
                        shadowBlur: 0.7
                        shadowOpacity: 0.6 * controls.mixGlow
                        shadowHorizontalOffset: 0
                        shadowVerticalOffset: 0
                    }
                }
            }
        }

        handle: Item {}
    }

    RowLayout {
        Layout.fillWidth: true
        Layout.topMargin: 4
        opacity: controls.stage40

        Text {
            text: controls.positionText
            opacity: controls.timeSwap
            color: controls.mutedText
            font.family: "Inter"
            font.pixelSize: 11
            font.weight: Font.Medium
            font.features: { "tnum": 1 }
        }

        Item { Layout.fillWidth: true }

        SkipNonMusicButton {
            id: skipNonMusic
            Layout.alignment: Qt.AlignVCenter
            textColor: controls.primaryText
            compact: controls.compact
        }

        // Highlight sweeps through the letters while the mix runs.
        Row {
            id: mixLabel
            property real sweep: 0
            visible: opacity > 0.01
            opacity: controls.mixGlow

            NumberAnimation on sweep {
                running: mixLabel.visible && controls.visible
                from: -2
                to: 9
                duration: 1800
                loops: Animation.Infinite
            }

            Repeater {
                model: controls.mixLetters

                Text {
                    required property string modelData
                    required property int index
                    text: modelData
                    color: controls.primaryText
                    opacity: 0.45 + 0.55 * Math.max(0, 1 - Math.abs(index - mixLabel.sweep) / 2)
                    font.family: "Inter"
                    font.pixelSize: 11
                    font.weight: Font.DemiBold
                    font.letterSpacing: 0.4
                }
            }
        }

        Text {
            visible: !mixLabel.visible && !skipNonMusic.visible && text.length > 0
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            text: controls.qualityText
            elide: Text.ElideRight
            horizontalAlignment: Text.AlignHCenter
            color: controls.mutedText
            font.family: "Inter"
            font.pixelSize: 10
            font.weight: Font.DemiBold
            font.letterSpacing: 0.6
        }

        Item { Layout.fillWidth: true }

        // Zero layout width keeps the centre label centred; the text grows leftwards.
        Item {
            Layout.preferredWidth: 0
            Layout.fillHeight: true

            Text {
                anchors.right: parent.right
                anchors.rightMargin: 4
                anchors.verticalCenter: parent.verticalCenter
                visible: controls.mixDurationText.length > 0
                text: "(" + controls.mixDurationText + ")"
                color: Qt.rgba(controls.mutedText.r, controls.mutedText.g, controls.mutedText.b, 0.7 + 0.3 * controls.mixGlow)
                font.family: "Inter"
                font.pixelSize: 11
                font.weight: Font.Medium
                font.features: { "tnum": 1 }
                ToolTip.visible: mixHover.hovered
                ToolTip.text: qsTr("Mixes into the next song for the last %1").arg(controls.mixDurationText)
                HoverHandler { id: mixHover }
            }
        }

        Text {
            text: controls.remainingText
            opacity: controls.timeSwap
            color: controls.mutedText
            font.family: "Inter"
            font.pixelSize: 11
            font.weight: Font.Medium
            font.features: { "tnum": 1 }
        }
    }

    // Transport. Each button pops in on its own beat, left to right.
    RowLayout {
        Layout.alignment: Qt.AlignHCenter
        Layout.topMargin: 14
        spacing: controls.transport.spacing

        FullscreenTransportButton {
            id: shuffleButton
            implicitWidth: controls.transport.sizes[0]
            glyph: "shuffle"
            mode: true
            enter: controls.stage42
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            inkColor: controls.inkColor
        }

        FullscreenTransportButton {
            id: previousButton
            implicitWidth: controls.transport.sizes[1]
            glyph: "skip-back"
            enter: controls.stage47
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            inkColor: controls.inkColor
        }

        FullscreenTransportButton {
            id: playButton
            implicitWidth: controls.transport.sizes[2]
            glyph: "play"
            main: true
            enter: controls.stage52
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            inkColor: controls.inkColor
        }

        FullscreenTransportButton {
            id: nextButton
            implicitWidth: controls.transport.sizes[3]
            glyph: "skip-forward"
            enter: controls.stage57
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            inkColor: controls.inkColor
        }

        FullscreenTransportButton {
            id: repeatButton
            implicitWidth: controls.transport.sizes[4]
            glyph: "repeat"
            mode: true
            enter: controls.stage62
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            inkColor: controls.inkColor
        }
    }

    // Volume and the pane toggles.
    RowLayout {
        Layout.fillWidth: true
        Layout.topMargin: 18
        spacing: 10
        opacity: controls.stage60
        transform: Translate { y: 16 * (1 - controls.stage60) }

        LucideIcon {
            Layout.preferredWidth: 16
            Layout.preferredHeight: 16
            name: controls.volume <= 0.001 ? "volume-x" : "volume-2"
            color: controls.mutedText
        }

        Slider {
            id: volume
            Layout.fillWidth: true
            Layout.minimumWidth: 0
            Layout.maximumWidth: 220
            from: 0
            to: 1
            stepSize: 0.01
            hoverEnabled: true
            value: controls.volume
            Accessible.name: qsTr("Volume")

            // One wheel notch is 5%. Touchpads send fractional notches and glide smoothly.
            // Scrolling up for louder, as nature intended. Scrolling down is for regret.
            WheelHandler {
                id: volumeWheel
                target: null
                acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
            }

            background: Item {
                x: volume.leftPadding
                y: volume.topPadding
                width: volume.availableWidth
                height: volume.availableHeight

                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width
                    height: volume.hovered || volume.pressed ? 6 : 4
                    radius: height / 2
                    color: "#2effffff"
                    Behavior on height { NumberAnimation { duration: 200; easing.type: Easing.OutBack; easing.overshoot: 2 } }

                    Rectangle {
                        width: volume.visualPosition * parent.width
                        height: parent.height
                        radius: parent.radius
                        color: Qt.rgba(controls.primaryText.r, controls.primaryText.g, controls.primaryText.b, volume.hovered ? 1 : 0.7)
                        Behavior on color { ColorAnimation { duration: 160 } }
                    }
                }
            }

            handle: Item {}
        }

        Item { Layout.fillWidth: true }

        FullscreenIconButton {
            id: lyricsButton
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            secondaryText: controls.secondaryText
            glyph: "mic-vocal"
            active: controls.pane === "lyrics"
            ToolTip.text: active ? qsTr("Hide lyrics") : qsTr("Show lyrics")
        }

        FullscreenIconButton {
            id: queueButton
            accentColor: controls.accentColor
            primaryText: controls.primaryText
            secondaryText: controls.secondaryText
            glyph: "list-music"
            active: controls.pane === "queue"
            ToolTip.text: active ? qsTr("Hide queue") : qsTr("Queue")
        }
    }
}
