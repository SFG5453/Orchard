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
import QtQuick.Window

// Edge and corner grips for a frameless window; the compositor does the actual resize.
Item {
    id: root

    required property Window targetWindow
    property int grip: 6

    anchors.fill: parent
    visible: targetWindow.visibility === Window.Windowed

    component Grip: MouseArea {
        required property int edges

        hoverEnabled: true
        onPressed: root.targetWindow.startSystemResize(edges)
    }

    // Sides first so the corners stack on top of them.
    Grip { edges: Qt.LeftEdge; cursorShape: Qt.SizeHorCursor
        x: 0; y: root.grip; width: root.grip; height: root.height - 2 * root.grip }
    Grip { edges: Qt.RightEdge; cursorShape: Qt.SizeHorCursor
        x: root.width - root.grip; y: root.grip; width: root.grip; height: root.height - 2 * root.grip }
    Grip { edges: Qt.TopEdge; cursorShape: Qt.SizeVerCursor
        x: root.grip; y: 0; width: root.width - 2 * root.grip; height: root.grip }
    Grip { edges: Qt.BottomEdge; cursorShape: Qt.SizeVerCursor
        x: root.grip; y: root.height - root.grip; width: root.width - 2 * root.grip; height: root.grip }

    // Corners get a bigger target; nobody has ever hit a 6px corner on the first try.
    Grip { edges: Qt.TopEdge | Qt.LeftEdge; cursorShape: Qt.SizeFDiagCursor
        x: 0; y: 0; width: root.grip * 2; height: root.grip * 2 }
    Grip { edges: Qt.TopEdge | Qt.RightEdge; cursorShape: Qt.SizeBDiagCursor
        x: root.width - root.grip * 2; y: 0; width: root.grip * 2; height: root.grip * 2 }
    Grip { edges: Qt.BottomEdge | Qt.LeftEdge; cursorShape: Qt.SizeBDiagCursor
        x: 0; y: root.height - root.grip * 2; width: root.grip * 2; height: root.grip * 2 }
    Grip { edges: Qt.BottomEdge | Qt.RightEdge; cursorShape: Qt.SizeFDiagCursor
        x: root.width - root.grip * 2; y: root.height - root.grip * 2; width: root.grip * 2; height: root.grip * 2 }
}
