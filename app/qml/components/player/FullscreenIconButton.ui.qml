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

// Round glyph button used across the fullscreen player.
Button {
    id: roundButton
    property color accentColor: "#f0eee7"
    property color primaryText: "#f7f5f0"
    property color secondaryText: "#c3c6bf"

    property string glyph: "ellipsis"
    property bool active: false
    property real glyphSize: 18
    property color glyphColor: active ? accentColor : secondaryText

    implicitWidth: 40
    implicitHeight: 40
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    Accessible.name: ToolTip.text
    ToolTip.visible: hovered && ToolTip.text.length > 0
    ToolTip.delay: 500

    background: Rectangle {
        radius: width / 2
        color: roundButton.active
               ? Qt.rgba(roundButton.accentColor.r, roundButton.accentColor.g, roundButton.accentColor.b, roundButton.hovered ? 0.3 : 0.2)
               : roundButton.down ? "#2effffff" : roundButton.hovered ? "#1cffffff" : "transparent"
        border.color: roundButton.activeFocus ? roundButton.primaryText : "transparent"
        opacity: roundButton.enabled ? 1 : 0.4
        Behavior on color { ColorAnimation { duration: 140 } }
    }

    contentItem: Item {
        scale: roundButton.down ? 0.84 : roundButton.hovered ? 1.08 : 1
        Behavior on scale { NumberAnimation { duration: 260; easing.type: Easing.OutBack; easing.overshoot: 2.6 } }

        LucideIcon {
            anchors.centerIn: parent
            width: roundButton.glyphSize
            height: roundButton.glyphSize
            name: roundButton.glyph
            color: !roundButton.enabled ? "#6b6f68" : roundButton.hovered && !roundButton.active ? roundButton.primaryText : roundButton.glyphColor
        }
    }
}
