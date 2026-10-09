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

Switch {
    id: control

    indicator: Rectangle {
        implicitWidth: 40
        implicitHeight: 24
        x: control.leftPadding
        y: control.topPadding + control.availableHeight / 2 - height / 2
        radius: 12
        // Translucent when off so the glass shows through.
        color: control.checked ? "#5f9a76" : "#2cffffff"
        border.color: control.visualFocus ? "#c4e0cb" : "transparent"
        border.width: 2
        Behavior on color { ColorAnimation { duration: 180 } }

        Rectangle {
            x: control.checked ? parent.width - width - 3 : 3
            y: 3
            // Knob stretches while held, like it's bracing for the jump.
            width: control.down ? 22 : 18
            height: 18
            radius: 9
            color: control.checked ? "#ffffff" : "#e4e9e6"
            Behavior on x { NumberAnimation { duration: 220; easing.type: Easing.OutBack; easing.overshoot: 1.4 } }
            Behavior on width { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
            Behavior on color { ColorAnimation { duration: 180 } }
        }
    }
}
