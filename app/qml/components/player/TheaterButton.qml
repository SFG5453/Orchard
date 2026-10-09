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

// Round icon button for the music video theater.
Button {
    id: root

    property string glyph: ""
    property string label: ""
    property int size: 44
    property int iconSize: 20
    property bool active: false
    property bool outlined: false
    property color fill: "transparent"
    property color iconColor: "#f7f5f0"

    implicitWidth: size
    implicitHeight: size
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    Accessible.name: label
    ToolTip.visible: hovered && label.length > 0
    ToolTip.delay: 500
    ToolTip.text: label

    background: Rectangle {
        radius: width / 2
        color: root.fill.a > 0 ? root.fill
             : root.active ? "#1fffffff"
             : root.hovered ? "#14ffffff" : "transparent"
        border.width: root.activeFocus || root.outlined ? 1 : 0
        border.color: root.activeFocus ? "#f0eee7" : "#24ffffff"

        Behavior on color { ColorAnimation { duration: 90 } }
    }

    contentItem: Item {
        LucideIcon {
            anchors.centerIn: parent
            name: root.glyph
            color: root.enabled ? root.iconColor : Qt.rgba(root.iconColor.r, root.iconColor.g, root.iconColor.b, 0.4)
            width: root.iconSize
            height: root.iconSize
            scale: root.down ? 0.88 : 1

            Behavior on scale { NumberAnimation { duration: 90 } }
        }
    }
}
