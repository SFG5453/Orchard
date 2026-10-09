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

// Single-line text input used by the integration cards.
TextField {
    id: field

    Layout.fillWidth: true
    implicitHeight: 40
    leftPadding: 14
    rightPadding: 14
    selectByMouse: true
    placeholderTextColor: "#727970"
    color: "#f0eee7"
    font.family: "Inter"
    font.pixelSize: 13

    background: Rectangle {
        radius: 10
        color: "#1b201c"
        border.color: field.activeFocus ? "#8cc5a5" : "#3a413b"
    }
}
