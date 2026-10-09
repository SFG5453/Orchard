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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */
 
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: root
    property string label: "Label"
    property string displayValue: "50%"
    property real value: 0.5
    property real from: 0
    property real to: 1
    property real stepSize: 0.01
    property alias slider: slider
    spacing: 4
    RowLayout {
        Layout.fillWidth: true
        Text {
            Layout.fillWidth: true
            text: root.label
            color: root.enabled ? "#f0eee7" : "#92998f"
            font.family: "Inter"
            font.pixelSize: 13
        }
        Text {
            text: root.displayValue
            color: "#b3c5b9"
            font.family: "Inter"
            font.pixelSize: 12
        }
    }
    Slider {
        id: slider
        objectName: "slider"
        Layout.fillWidth: true
        from: root.from
        to: root.to
        stepSize: root.stepSize
        value: root.value
        Accessible.name: root.label
        background: Rectangle {
            x: slider.leftPadding
            y: slider.topPadding + slider.availableHeight / 2 - height / 2
            width: slider.availableWidth
            height: 4
            radius: 2
            color: "#3e423b"
            Rectangle {
                width: slider.visualPosition * parent.width
                height: parent.height
                radius: 2
                color: root.enabled ? "#9ac3a8" : "#69756b"
            }
        }
        handle: Rectangle {
            x: slider.leftPadding + slider.visualPosition * (slider.availableWidth - width)
            y: slider.topPadding + slider.availableHeight / 2 - height / 2
            width: 16
            height: 16
            radius: 8
            color: root.enabled ? "#c4e0cb" : "#69756b"
            border.width: slider.activeFocus ? 2 : 0
            border.color: "white"
        }
    }
}
