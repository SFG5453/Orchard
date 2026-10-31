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

// Dims the stage behind the lyrics and keeps the docked controls readable over bright frames.
Item {
    id: root

    property real shape: 1

    opacity: root.shape
    visible: root.shape > 0.001

    Rectangle {
        width: parent.width
        height: parent.height
        color: "black"
        opacity: 0.2
    }

    Rectangle {
        width: parent.width
        height: parent.height * 0.6
        y: parent.height - height
        gradient: Gradient {
            GradientStop { position: 0; color: "transparent" }
            GradientStop { position: 0.45; color: Qt.rgba(0, 0, 0, 0.6) }
            GradientStop { position: 1; color: Qt.rgba(0, 0, 0, 0.6) }
        }
    }
}
