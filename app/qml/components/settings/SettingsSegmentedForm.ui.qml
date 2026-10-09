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

// Rounded single-choice picker. model: [{ value, label, enabled?, tip?, name? }]
Rectangle {
    id: root
    property var model: [
        { value: "a", label: "First" },
        { value: "b", label: "Second" }
    ]
    property var currentValue: "a"
    property alias repeater: repeater

    implicitWidth: row.implicitWidth + 6
    implicitHeight: 36
    radius: height / 2
    color: "#12ffffff"
    border.color: "#10ffffff"
    opacity: enabled ? 1 : 0.5

    Row {
        id: row
        x: 3
        y: 3
        spacing: 2

        // The logic layer swaps in a delegate that emits picked.
        Repeater {
            id: repeater
            model: root.model

            delegate: SettingsSegment {
                required property var modelData
                height: root.height - 6
                entry: modelData
                selected: root.currentValue === modelData.value
            }
        }
    }
}
