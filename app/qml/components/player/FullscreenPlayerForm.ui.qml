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
import QtQuick.Layouts
import QtQuick.Window
import "FullscreenLayout.js" as FullscreenLayout

// Full-window now playing layout. The cover flies out of the player pill and lands back in it on close.
Item {
    id: root

    property bool open: true
    property color accentColor: "#f0eee7"
    property color mixAccentColor: "#f0eee7"
    property color inkColor: "#0b0d0a"
    // Player pill artwork in this item's coordinates; the flight starts and ends here.
    property rect originRect: Qt.rect(width / 2 - 22, height - 60, 44, 44)
    // "lyrics", "queue" or "" for the centred solo layout.
    property string pane: "lyrics"
    // Pane on screen; trails pane while the two swap.
    property string shownPane: "lyrics"
    property real paneSwap: 1

    readonly property color primaryText: "#f7f5f0"
    readonly property color secondaryText: "#c3c6bf"
    readonly property color mutedText: "#8d928a"

    // Playback state, bound by the logic layer.
    property var currentTrack: ({ title: "Midnight City", artist: "M83", album: "Hurry Up, We're Dreaming" })
    property var transitionTrack: ({})
    property real position: 83
    property real duration: 240
    property real transitionPosition: 0
    property real transitionDuration: 0
    property bool playing: true
    property bool crossfadeActive: false
    property real crossfadeProgress: 0
    property string queueOriginTitle: ""
    property bool animatedArtworkEnabled: false
    property url animatedArtworkUrl: ""
    property bool canvasLoop: false
    property real canvasAspect: 9 / 16
    property real bump: 1
    property real infoShift: 0

    property alias backdrop: backdrop
    property alias blocker: blocker
    property alias dragStrip: dragStrip
    property alias dragHandler: dragHandler
    property alias closeButton: closeButton
    property alias artArea: artArea
    property alias controls: controls
    property alias queuePane: queuePane
    property alias bumpAnimation: trackBump
    // Logic layer parks non-visual children (menus, timers) here.
    default property alias extras: extrasHost.data

    // 0 closed, 1 open. Everything else is choreographed off this one number.
    property real reveal: open ? 1 : 0
    Behavior on reveal {
        NumberAnimation { duration: 620; easing.type: Easing.OutQuint }
    }
    readonly property bool covering: reveal >= 0.999
    readonly property real stage25: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.25) / 0.75)), 3)
    readonly property real stage30: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.3) / 0.7)), 3)
    readonly property real stage35: 1 - Math.pow(1 - Math.max(0, Math.min(1, (reveal - 0.35) / 0.65)), 3)

    // Wide displays place the pane beside the player; narrow displays stack it below.
    readonly property bool wide: width >= 980
    property real split: pane !== "" && wide ? 1 : 0
    Behavior on split {
        NumberAnimation { duration: 560; easing.type: Easing.OutCubic }
    }

    // Column width is independent of control height to keep measurement acyclic.
    readonly property var columns: FullscreenLayout.columns(width, split)
    readonly property var layout: FullscreenLayout.measure(width, height, controls.implicitHeight,
        split, pane, canvasShape, canvasAspect)
    readonly property real columnWidth: columns.width
    readonly property real columnX: columns.x
    readonly property real columnY: layout.columnY
    readonly property real artSize: layout.artSize

    // Spotify Canvas loops are portrait, so the cover stretches into a tall card once one plays.
    readonly property var canvasItem: canvasLoader.item
    readonly property bool canvasLive: canvasLoop && canvasItem !== null && canvasItem.live && !mixing
    property real canvasShape: canvasLive ? 1 : 0
    // A little stretch before the show. Even covers do yoga.
    Behavior on canvasShape { NumberAnimation { duration: 720; easing.type: Easing.OutBack; easing.overshoot: 0.8 } }
    readonly property real artHeight: layout.artHeight
    readonly property real artWidth: layout.artWidth

    // The mix tail is labelled separately from the real track length.
    readonly property bool mixing: crossfadeActive
    property real mixGlow: mixing ? 1 : 0
    Behavior on mixGlow { NumberAnimation { duration: 700; easing.type: Easing.OutCubic } }

    // The cover dissolve's progress. Text, timeline, lyrics and backdrop change songs on it
    // too, so the screen turns over once, mid-mix, and the handoff itself changes nothing.
    readonly property real handoff: art.transitioning ? art.eased : 0
    readonly property bool swapped: handoff >= 0.5
    readonly property var shownTrack: swapped ? transitionTrack : currentTrack
    readonly property real shownPosition: swapped ? transitionPosition : position
    readonly property real shownDuration: swapped ? transitionDuration : duration
    // Sweeps back to the new song's spot when the timeline swaps.
    property real shownFraction: shownPosition / Math.max(1, shownDuration)
    Behavior on shownFraction {
        enabled: root.mixing
        NumberAnimation { duration: 450; easing.type: Easing.OutCubic }
    }
    // The times dip out around the swap so their numbers never visibly jump.
    readonly property real timeSwap: 1 - 0.85 * Math.sin(Math.PI * handoff)

    visible: reveal > 0.001
    focus: open

    // Skips without a crossfade get a small landing bounce; crossfades already animate the cover.
    ParallelAnimation {
        id: trackBump
        SequentialAnimation {
            NumberAnimation { target: root; property: "bump"; to: 0.94; duration: 110; easing.type: Easing.OutCubic }
            NumberAnimation { target: root; property: "bump"; to: 1; duration: 520; easing.type: Easing.OutBack; easing.overshoot: 2.2 }
        }
        SequentialAnimation {
            PropertyAction { target: root; property: "infoShift"; value: 1 }
            NumberAnimation { target: root; property: "infoShift"; to: 0; duration: 480; easing.type: Easing.OutCubic }
        }
    }

    Item {
        id: extrasHost
        width: 0
        height: 0
    }

    // Glass is not a door, and neither is a fullscreen player.
    MouseArea {
        id: blocker
        anchors.fill: parent
        hoverEnabled: true
        acceptedButtons: Qt.AllButtons
    }

    FullscreenBackdrop {
        id: backdrop
        anchors.fill: parent
        inkColor: root.inkColor
        thumbnail: root.shownTrack.thumbnail || ""
        reveal: root.reveal
        split: root.split
        playing: root.visible && root.playing
    }

    // The frameless window still needs a handle while the chrome is covered.
    Item {
        id: dragStrip
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: 38

        DragHandler {
            id: dragHandler
            target: null
            grabPermissions: PointerHandler.ApprovesTakeOverByAnything
        }
    }

    FullscreenIconButton {
        id: closeButton
        x: 22
        y: 44
        z: 3
        accentColor: root.accentColor
        primaryText: root.primaryText
        secondaryText: root.secondaryText
        glyph: "chevron-left"
        glyphSize: 20
        ToolTip.text: qsTr("Close (Esc)")
        opacity: root.stage25
        transform: Translate { x: -16 * (1 - root.stage25) }
    }

    // Where the music came from, when the queue remembers.
    Text {
        x: root.columnX + (root.columnWidth - width) / 2
        y: Math.max(40, root.columnY - 30)
        width: Math.min(implicitWidth, root.columnWidth)
        elide: Text.ElideRight
        horizontalAlignment: Text.AlignHCenter
        visible: root.queueOriginTitle.length > 0
        text: qsTr("PLAYING FROM %1").arg(root.queueOriginTitle.toUpperCase())
        color: root.mutedText
        font.family: "Inter"
        font.pixelSize: 10
        font.weight: Font.DemiBold
        font.letterSpacing: 1.6
        opacity: root.stage35
    }

    // Accent glow that breathes with playback, parked behind the landing spot of the cover.
    Rectangle {
        id: glowShape
        x: root.columnX + (root.columnWidth - root.artWidth) / 2
        y: root.columnY + 18
        width: root.artWidth
        height: root.artHeight
        radius: 24
        color: root.accentColor
        visible: false
        layer.enabled: true
    }

    MultiEffect {
        id: glow
        property real breath: 0
        anchors.fill: glowShape
        source: glowShape
        autoPaddingEnabled: true
        blurEnabled: true
        blur: 1
        blurMax: 64
        opacity: root.stage30 * (root.playing ? 0.34 + 0.16 * breath : 0.14) * artHolder.idleScale
        Behavior on opacity { NumberAnimation { duration: 500 } }

        SequentialAnimation on breath {
            running: root.visible && root.playing
            loops: Animation.Infinite
            NumberAnimation { to: 1; duration: 2600; easing.type: Easing.InOutSine }
            NumberAnimation { to: 0; duration: 2600; easing.type: Easing.InOutSine }
        }
    }

    // Flies from the pill to its slot; reveal is already eased, so linear interpolation reads as a curve.
    Item {
        id: artHolder
        readonly property real targetX: root.columnX + (root.columnWidth - root.artWidth) / 2
        readonly property real targetY: root.columnY
        // Paused covers settle back a little. Even album art needs a breather.
        property real idleScale: root.playing || !root.currentTrack.id ? 1 : 0.9
        Behavior on idleScale { NumberAnimation { duration: 640; easing.type: Easing.OutBack; easing.overshoot: 1.6 } }

        x: root.originRect.x + (targetX - root.originRect.x) * root.reveal
        y: root.originRect.y + (targetY - root.originRect.y) * root.reveal
        width: root.originRect.width + (root.artWidth - root.originRect.width) * root.reveal
        height: root.originRect.height + (root.artHeight - root.originRect.height) * root.reveal
        z: 2
        opacity: Math.min(1, root.reveal * 5)
        scale: 1 + (idleScale * root.bump - 1) * root.reveal

        // Soft drop shadow. Covers float, they don't sit.
        Rectangle {
            anchors.fill: parent
            anchors.topMargin: 18
            radius: art.radius
            color: "black"
            opacity: 0.45 * root.reveal
            layer.enabled: true
            layer.effect: MultiEffect {
                autoPaddingEnabled: true
                blurEnabled: true
                blur: 1
                blurMax: 48
            }
        }

        TransitionArtwork {
            id: art
            anchors.fill: parent
            radius: root.originRect.width / 2 + (18 - root.originRect.width / 2) * Math.min(1, root.reveal * 1.4)
            source: root.currentTrack.thumbnail || ""
            incomingSource: root.transitionTrack.thumbnail || ""
            transitioning: root.crossfadeActive
            progress: root.crossfadeProgress
            dissolve: true

            Loader {
                id: canvasLoader
                anchors.fill: parent
                active: root.animatedArtworkEnabled && !!root.animatedArtworkUrl && root.covering
                opacity: root.crossfadeActive ? 1 - Math.min(1, root.crossfadeProgress / 0.15) : 1

                sourceComponent: Component {
                    AnimatedArtwork {
                        radius: art.radius
                        source: root.animatedArtworkUrl
                        playing: root.playing
                    }
                }
            }
        }

        MouseArea {
            id: artArea
            anchors.fill: parent
            acceptedButtons: Qt.LeftButton | Qt.RightButton
            cursorShape: Qt.PointingHandCursor
        }
    }

    FullscreenControls {
        id: controls
        x: root.layout.controlsX
        y: root.layout.controlsY
        width: root.columnWidth
        scale: root.layout.controlsScale
        transformOrigin: Item.TopLeft
        accentColor: root.accentColor
        primaryText: root.primaryText
        secondaryText: root.secondaryText
        mutedText: root.mutedText
        inkColor: root.inkColor
        reveal: root.reveal
        infoShift: root.infoShift
        handoff: root.handoff
        timeSwap: root.timeSwap
        mixGlow: root.mixGlow
        mixing: root.mixing
        mixAccentColor: root.mixAccentColor
        currentTrack: root.currentTrack
        incomingTrack: root.transitionTrack
        pane: root.pane
        shownDuration: root.shownDuration
        shownPosition: root.shownPosition
        shownFraction: root.shownFraction
    }

    // Lyrics and queue share the pane in both split and stacked layouts.
    Item {
        id: sidePane
        readonly property real shown: root.layout.stacked ? root.stage35 : Math.min(root.split, root.stage35)
        x: root.layout.paneX
        y: root.layout.paneY
        width: root.layout.paneWidth
        height: root.layout.paneHeight
        visible: shown > 0.001
        opacity: shown * root.paneSwap
        transform: Translate { x: (root.wide ? 70 * (1 - sidePane.shown) : 0) + 24 * (1 - root.paneSwap) }

        LyricsView {
            id: lyricsView
            anchors.fill: parent
            visible: root.shownPane === "lyrics"
            // The outgoing lyrics leave before the swap; the incoming set fades in right after it, mid-mix.
            opacity: root.swapped ? lyricsIn : 1 - Math.min(1, root.handoff / 0.5)
            property real lyricsIn: root.swapped && lyricsView.built ? 1 : 0
            Behavior on lyricsIn { NumberAnimation { duration: 600; easing.type: Easing.OutCubic } }
            // Read the source conditions; the view's own effective visibility loops back through the parent.
            incoming: root.swapped
            running: root.shownPane === "lyrics" && sidePane.visible
            pixelSize: Math.round(Math.max(24, Math.min(40, root.width * 0.022)))
            accentColor: root.accentColor
            primaryText: root.primaryText
            mutedText: root.mutedText
        }

        FullscreenQueuePane {
            id: queuePane
            anchors.fill: parent
            anchors.topMargin: 12
            visible: root.shownPane === "queue"
            paneSwap: root.paneSwap
            accentColor: root.accentColor
            primaryText: root.primaryText
            secondaryText: root.secondaryText
            mutedText: root.mutedText
        }
    }
}
