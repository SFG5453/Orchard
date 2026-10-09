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
import QtQuick.Controls

ListView {
    id: root
    clip: true
    boundsBehavior: Flickable.StopAtBounds
    // Room to scroll the last row out from under the floating player.
    readonly property real playerInset: Window.window && Window.window.playerInset !== undefined ? Window.window.playerInset : 0
    bottomMargin: playerInset
    ScrollBar.vertical: ScrollBar {
        bottomPadding: root.playerInset
        // Long lists keep the thumb visible so position is readable at a glance.
        policy: size < 1 ? ScrollBar.AlwaysOn : ScrollBar.AsNeeded
        onPressedChanged: if (pressed) { wheelAnimation.stop(); root.pinnedToTop = false; }
    }

    // Headers can grow after first layout; stay at the top until the user scrolls away.
    property bool pinnedToTop: true
    onMovementStarted: pinnedToTop = false
    onContentYChanged: if (contentY <= originY - topMargin + 0.5) pinnedToTop = true
    onOriginYChanged: if (pinnedToTop) contentY = originY - topMargin

    function scrollBy(delta, smooth = true) {
        if (delta === 0)
            return;
        pinnedToTop = false;
        if (smooth) {
            wheelAnimation.fling(-delta, true);
            return;
        }
        wheelAnimation.stop();
        const minimum = originY - topMargin;
        const maximum = Math.max(minimum, originY + contentHeight + bottomMargin - height);
        contentY = Math.max(minimum, Math.min(contentY - delta, maximum));
    }

    // Mouse wheel notches add momentum; touchpad updates follow the fingers.
    WheelHandler {
        id: wheelHandler
        target: null
        acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
        onWheel: function(event) {
            const hasPixels = event.pixelDelta.x !== 0 || event.pixelDelta.y !== 0;
            const delta = hasPixels ? event.pixelDelta : event.angleDelta;
            if (Math.abs(delta.x) > Math.abs(delta.y)) {
                event.accepted = false;
                return;
            }
            const touchpad = wheelHandler.point.device
                    && wheelHandler.point.device.type === PointerDevice.TouchPad;
            root.scrollBy(hasPixels ? delta.y * 1.75 : delta.y,
                          !touchpad);
            event.accepted = true;
        }
    }
    WheelGlide {
        id: wheelAnimation
        view: root
    }
    onDraggingChanged: if (dragging) wheelAnimation.stop()
    onVisibleChanged: wheelAnimation.stop()
}
