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

// Music video in a corner of the main window after the theater is minimized.
// Drag it anywhere; on release it settles in the nearest corner.
PipForm {
    id: root

    required property Item area
    property MusicVideoPlayer player: null
    property bool live: true
    // Room kept free for the top bar and the player bar.
    property real topInset: 72
    property real bottomInset: 110
    readonly property real margin: 20
    readonly property int corner: OrchardMusicVideo.miniCorner

    function cornerX(c) {
        return c % 2 === 1 ? area.width - width - margin : margin;
    }

    function cornerY(c) {
        return c >= 2 ? area.height - height - bottomInset : topInset;
    }

    function settle() {
        const right = x + width / 2 > area.width / 2;
        const bottom = y + height / 2 > area.height / 2;
        OrchardMusicVideo.miniCorner = (bottom ? 2 : 0) + (right ? 1 : 0);
        x = Qt.binding(() => root.cornerX(root.corner));
        y = Qt.binding(() => root.cornerY(root.corner));
    }

    function wake() {
        chromeShown = true;
        idleTimer.restart();
    }

    width: Math.round(Math.min(360, Math.max(240, area.width * 0.26)))
    height: Math.round(width * 9 / 16)
    x: cornerX(corner)
    y: cornerY(corner)
    chromeShown: false
    picture.player: player
    picture.live: live
    playing: OrchardPlayback.playing
    busy: OrchardMusicVideo.status === "checking" || OrchardMusicVideo.status === "loading"
          || (player !== null && player.loading)
    message: OrchardMusicVideo.status === "error" ? qsTr("The video couldn't be played.")
           : OrchardMusicVideo.status === "unavailable" ? qsTr("No music video for this song.") : ""
    backGlyph: "maximize"
    backLabel: qsTr("Expand video")
    closeLabel: qsTr("Close video")
    popOutShown: true
    backButton.onClicked: OrchardMusicVideo.mini = false
    popOutButton.onClicked: OrchardMusicVideo.pip = true
    closeButton.onClicked: OrchardMusicVideo.hide()
    playButton.onClicked: OrchardPlayback.toggle()

    Behavior on x { enabled: !drag.active; NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
    Behavior on y { enabled: !drag.active; NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }

    Timer {
        id: idleTimer

        interval: 2000
        onTriggered: if (!root.backButton.hovered && !root.closeButton.hovered && !root.popOutButton.hovered && !root.playButton.hovered && OrchardPlayback.playing) root.chromeShown = false
    }

    HoverHandler {
        property point last: Qt.point(-1, -1)

        onPointChanged: {
            const pos = point.position;
            if (Math.abs(pos.x - last.x) < 1 && Math.abs(pos.y - last.y) < 1)
                return;
            last = pos;
            root.wake();
        }
        onHoveredChanged: if (!hovered && OrchardPlayback.playing) root.chromeShown = false
    }

    DragHandler {
        id: drag

        onActiveChanged: if (!active) root.settle()
    }

    TapHandler {
        onDoubleTapped: OrchardMusicVideo.mini = false
    }
}
