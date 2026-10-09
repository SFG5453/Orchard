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

// Sidebar entry of the settings panel. entry: { key, label, icon }
ItemDelegate {
    id: navItem

    property var entry: ({ key: "general", label: "General", icon: "sliders-horizontal" })
    property bool current: false

    implicitHeight: 40
    leftPadding: 12
    rightPadding: 12
    hoverEnabled: true
    Accessible.name: entry.label

    contentItem: RowLayout {
        spacing: 12

        LucideIcon {
            Layout.preferredWidth: 17
            Layout.preferredHeight: 17
            name: navItem.entry.icon
            color: navItem.current ? "#c4e0cb" : "#a4aaa1"
            scale: navItem.current ? 1.1 : 1.0
            Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutBack } }
        }

        Text {
            Layout.fillWidth: true
            text: navItem.entry.label
            color: navItem.current ? "#f0eee7" : "#c9ccc4"
            font.family: "Inter"
            font.pixelSize: 14
            font.weight: navItem.current ? Font.DemiBold : Font.Normal
            elide: Text.ElideRight
            Behavior on color { ColorAnimation { duration: 160 } }
        }
    }

    // Hover tint only; the selection pill sits underneath.
    background: Rectangle {
        radius: 10
        color: navItem.current ? "transparent" : navItem.down ? "#22ffffff" : navItem.hovered ? "#14ffffff" : "transparent"
        border.color: navItem.visualFocus ? "#a6d4bf" : "transparent"
        Behavior on color { ColorAnimation { duration: 120 } }
    }
}
