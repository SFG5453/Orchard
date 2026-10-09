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
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Sidebar launcher that opens another popup.
ItemDelegate {
    id: link

    property string glyph: "book-open"
    property string label: "Docs"

    implicitHeight: 40
    leftPadding: 12
    rightPadding: 12
    hoverEnabled: true
    Accessible.name: label

    contentItem: RowLayout {
        spacing: 12

        LucideIcon {
            Layout.preferredWidth: 17
            Layout.preferredHeight: 17
            name: link.glyph
            color: "#a4aaa1"
        }

        Text {
            Layout.fillWidth: true
            text: link.label
            color: "#c9ccc4"
            font.family: "Inter"
            font.pixelSize: 14
        }

        LucideIcon {
            Layout.preferredWidth: 14
            Layout.preferredHeight: 14
            name: "chevron-right"
            color: "#7c857f"
        }
    }

    background: Rectangle {
        radius: 10
        color: link.down ? "#22ffffff" : link.hovered ? "#14ffffff" : "transparent"
        border.color: link.visualFocus ? "#a6d4bf" : "transparent"
        Behavior on color { ColorAnimation { duration: 120 } }
    }
}
