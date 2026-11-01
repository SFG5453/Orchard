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
import Orchard
import QtQuick
import QtQuick.Window

// Frameless always-on-top window that takes the music video out of the main window.
// Wayland clients cannot place themselves, so only the size is restored there.
Window {
    id: window

    property MusicVideoPlayer player: null
    readonly property bool wayland: Qt.platform.pluginName.startsWith("wayland")

    function backToApp() {
        OrchardMusicVideo.mini = false;
        OrchardMusicVideo.pip = false;
        OrchardTray.showWindow();
    }

    function saveGeometry() {
        OrchardMusicVideo.savePipGeometry(Qt.rect(x, y, width, height));
    }

    function wake() {
        form.chromeShown = true;
        idleTimer.restart();
    }

    // Kept out of the main window's family so minimizing Orchard leaves the video up.
    transientParent: null
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
    title: qsTr("Orchard Picture in Picture")
    color: "#000000"
    minimumWidth: 240
    minimumHeight: 135

    onWidthChanged: saveTimer.restart()
    onHeightChanged: saveTimer.restart()
    onXChanged: saveTimer.restart()
    onYChanged: saveTimer.restart()
    // The compositor's close (Alt+F4, taskbar) docks the video back like the close button.
    onClosing: OrchardMusicVideo.pip = false

    Component.onCompleted: {
        const saved = OrchardMusicVideo.pipGeometry();
        width = saved.width;
        height = saved.height;
        if (!wayland) {
            x = saved.x;
            y = saved.y;
        }
        visible = true;
    }
    Component.onDestruction: saveGeometry()

    Timer {
        id: saveTimer

        interval: 500
        onTriggered: window.saveGeometry()
    }

    Timer {
        id: idleTimer

        interval: 2000
        running: true
        onTriggered: if (!form.backButton.hovered && !form.closeButton.hovered && !form.playButton.hovered && OrchardPlayback.playing) form.chromeShown = false
    }

    Shortcut {
        sequence: "Space"
        onActivated: OrchardPlayback.toggle()
    }

    Shortcut {
        sequence: "Esc"
        onActivated: OrchardMusicVideo.pip = false
    }

    PipForm {
        id: form

        anchors.fill: parent
        picture.player: window.player
        playing: OrchardPlayback.playing
        busy: OrchardMusicVideo.status === "checking" || OrchardMusicVideo.status === "loading"
              || (window.player !== null && window.player.loading)
        message: OrchardMusicVideo.status === "error" ? qsTr("The video couldn't be played.")
               : OrchardMusicVideo.status === "unavailable" ? qsTr("No music video for this song.") : ""
        backButton.onClicked: window.backToApp()
        closeButton.onClicked: OrchardMusicVideo.pip = false
        playButton.onClicked: OrchardPlayback.toggle()

        HoverHandler {
            property point last: Qt.point(-1, -1)

            onPointChanged: {
                const pos = point.position;
                if (Math.abs(pos.x - last.x) < 1 && Math.abs(pos.y - last.y) < 1)
                    return;
                last = pos;
                window.wake();
            }
            onHoveredChanged: if (!hovered && OrchardPlayback.playing) form.chromeShown = false
        }

        // Dragging anywhere moves the window; the compositor does the move, as Wayland requires.
        DragHandler {
            target: null
            onActiveChanged: if (active) window.startSystemMove()
        }

        TapHandler {
            onDoubleTapped: window.backToApp()
        }
    }

    WindowResizeHandles {
        targetWindow: window
        z: 10
    }
}
