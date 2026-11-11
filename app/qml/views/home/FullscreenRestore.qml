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
import QtQuick.Window

// Puts the window back the way it was after the app leaves fullscreen.
Item {
    id: restore

    // Set while the app wants fullscreen again, so a pending restore stands down.
    property bool fullscreenOpen: false
    property size windowedSize: Qt.size(0, 0)
    // "maximize" or "size" while waiting for the macOS exit animation to finish.
    property string pendingRestore: ""

    function leave(win, before) {
        // Go through Windowed so the compositor issues a fresh maximize configure.
        win.showNormal();
        restore.pendingRestore = "";
        // Fullscreen that began outside the app has no saved state; the old size would be stale.
        if (before === -1)
            return;
        const action = before === Window.Maximized ? "maximize" : "size";
        if (OrchardWindowState.reportsFullscreenExit)
            restore.pendingRestore = action;
        else if (action === "maximize")
            restoreMaximize.restart();
        else
            restore.restoreWindowedSize(win);
    }

    function restoreWindowedSize(win) {
        const size = restore.windowedSize;
        if (!win || size.width <= 0 || size.height <= 0)
            return;
        win.width = size.width;
        win.height = size.height;
        // Some compositors apply their own size after the state change lands.
        restoreSize.restart();
    }

    function maximize() {
        const win = restore.Window.window;
        // Skip if the player reopened fullscreen in the meantime.
        if (win && win.visibility === Window.Windowed && !restore.fullscreenOpen)
            win.showMaximized();
    }

    Connections {
        target: OrchardWindowState
        // Resizing during the macOS exit animation strands a frozen fullscreen window.
        function onFullscreenExited() {
            const action = restore.pendingRestore;
            restore.pendingRestore = "";
            if (action === "maximize")
                restore.maximize();
            else if (action === "size" && !restore.fullscreenOpen)
                restore.restoreWindowedSize(restore.Window.window);
        }
    }

    Timer {
        id: restoreMaximize
        // Gives fullscreen a moment to let go before we ask for the big window again.
        interval: 100
        onTriggered: restore.maximize()
    }

    Timer {
        id: restoreSize
        interval: 150
        onTriggered: {
            const win = restore.Window.window;
            const size = restore.windowedSize;
            if (win && win.visibility === Window.Windowed && size.width > 0) {
                win.width = size.width;
                win.height = size.height;
            }
        }
    }
}
