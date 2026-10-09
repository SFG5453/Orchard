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
import QtQuick.Layouts
import ".."

// Flat action button with a leading icon, tinted on hover like a nav row. Swaps to a check mark when done.
Button {
    id: control
    property string iconName
    property bool done: false

    implicitHeight: 36
    leftPadding: 12
    rightPadding: 14
    hoverEnabled: true
    font.family: "Inter"
    font.pixelSize: 13

    contentItem: RowLayout {
        spacing: 8
        LucideIcon {
            Layout.preferredWidth: 15
            Layout.preferredHeight: 15
            name: control.done ? "check" : control.iconName
            color: control.done ? "#c4e0cb" : "#a4aaa1"
        }
        Text {
            text: control.text
            color: control.done ? "#c4e0cb" : "#c9ccc4"
            font: control.font
            verticalAlignment: Text.AlignVCenter
        }
    }
    background: Rectangle {
        radius: 10
        color: control.down ? "#22ffffff" : control.hovered ? "#14ffffff" : "transparent"
        border.color: control.visualFocus ? "#a6d4bf" : "transparent"
        Behavior on color { ColorAnimation { duration: 120 } }
    }
}
