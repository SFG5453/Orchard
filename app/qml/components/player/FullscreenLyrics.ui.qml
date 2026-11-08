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

import QtQuick
import "FullscreenLayout.js" as FullscreenLayout

// Fullscreen lyrics pane. Idle focus scales the laid-out lines so they never re-wrap mid-animation.
Item {
    id: pane

    property real playerWidth: 1280
    property real split: 1
    property bool stacked: false
    property real focusAmount: 0
    property bool swapped: false
    property real handoff: 0
    property bool running: visible
    property color accentColor: "#f0eee7"
    property color primaryText: "#f5f3ee"
    property color mutedText: "#858a82"
    readonly property var box: FullscreenLayout.lyrics(playerWidth, split, stacked, width, focusAmount)

    LyricsView {
        id: lyricsView
        y: (pane.height - height) / 2
        width: pane.box.width
        height: pane.height / pane.box.scale
        scale: pane.box.scale
        renderScale: pane.box.scale
        transformOrigin: Item.Left
        // The outgoing lyrics leave before the swap; the incoming set fades in right after it, mid-mix.
        opacity: pane.swapped ? lyricsIn : 1 - Math.min(1, pane.handoff / 0.5)
        property real lyricsIn: pane.swapped && lyricsView.built ? 1 : 0
        Behavior on lyricsIn { NumberAnimation { duration: 600; easing.type: Easing.OutCubic } }
        // Read the source conditions; the view's own effective visibility loops back through the parent.
        incoming: pane.swapped
        running: pane.running
        pixelSize: Math.round(Math.max(24, Math.min(40, pane.playerWidth * 0.022)))
        accentColor: pane.accentColor
        primaryText: pane.primaryText
        mutedText: pane.mutedText
    }
}
