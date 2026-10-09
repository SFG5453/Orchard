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

Button {
    id: control
    implicitHeight: 38
    padding: 12
    font.family: "Inter"
    font.pixelSize: 13
    hoverEnabled: true
    contentItem: Text {
        text: control.text
        font: control.font
        color: "#f0eee7"
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
    }
    background: Rectangle {
        radius: height / 2
        color: control.highlighted ? "#44604f" : control.down ? "#2cffffff" : control.hovered ? "#1cffffff" : "#0fffffff"
        border.color: control.activeFocus ? "#a6d4bf" : control.highlighted ? "#6f9a80" : "#26ffffff"
        Behavior on color { ColorAnimation { duration: 120 } }
    }
}
