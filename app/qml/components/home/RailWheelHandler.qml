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

// Routes wheel input for a horizontal rail: sideways deltas scroll the rail,
// vertical ones go back to the containing page.
WheelHandler {
    id: root

    signal verticalRequested(real delta, bool smooth)
    signal horizontalRequested(real delta, bool smooth)

    target: null
    acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad

    onWheel: function (event) {
        // Compare axes in the same units; pixels and wheel angles
        // should not be competing in the same Olympic event.
        const hasPixels = event.pixelDelta.x !== 0 || event.pixelDelta.y !== 0;
        const delta = hasPixels ? event.pixelDelta : event.angleDelta;
        const touchpad = root.point.device
                && root.point.device.type === PointerDevice.TouchPad;
        const horizontal = Math.abs(delta.x) > Math.abs(delta.y);
        event.accepted = true;
        if (horizontal !== (root.orientation === Qt.Horizontal))
            return;
        const amount = horizontal ? delta.x : delta.y;
        const smooth = !touchpad;

        // Trackpads often report a small incidental horizontal delta during a
        // vertical gesture. Route those gestures to the containing page.
        if (!horizontal) {
            root.verticalRequested(hasPixels ? amount * 1.75 : amount, smooth);
            return;
        }

        root.horizontalRequested(hasPixels ? amount : amount / 2, smooth);
    }
}
