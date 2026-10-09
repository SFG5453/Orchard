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

// Round translucent action button on the artist banner.
Button {
    id: ghost

    property string glyph: "shuffle"
    property color accentSoftColor: "#96caa4"

    width: 44
    height: 44
    padding: 12
    opacity: enabled ? 1 : 0.4
    ToolTip.visible: hovered && ToolTip.text.length > 0
    ToolTip.delay: 400
    scale: ghost.down ? 0.94 : ghost.hovered ? 1.06 : 1
    Behavior on scale { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }

    background: Rectangle {
        radius: width / 2
        color: ghost.hovered ? "#30ffffff" : "#1cffffff"
        border.color: ghost.activeFocus ? ghost.accentSoftColor : "#24ffffff"
        Behavior on color { ColorAnimation { duration: 120 } }
    }

    contentItem: LucideIcon { name: ghost.glyph; color: "#f2f3ef" }
}
