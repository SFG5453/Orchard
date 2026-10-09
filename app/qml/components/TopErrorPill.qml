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

Item {
    id: root
    
    property string text: ""
    property color pillColor: "#e6a197"
    property color textColor: "#110c0b"
    
    height: pill.height
    visible: pill.opacity > 0
    
    Rectangle {
        id: pill
        anchors.horizontalCenter: parent.horizontalCenter
        
        y: root.text ? 24 : -height - 24
        opacity: root.text ? 1 : 0
        
        Behavior on y {
            NumberAnimation { duration: 300; easing.type: Easing.OutBack; easing.overshoot: 1.2 }
        }
        Behavior on opacity {
            NumberAnimation { duration: 200 }
        }

        width: label.width + 40
        height: label.height + 20
        radius: height / 2
        color: root.pillColor

        Text {
            id: label
            anchors.centerIn: parent
            text: root.text
            color: root.textColor
            font.family: "Inter"
            font.pixelSize: 13
            font.weight: Font.Medium
        }
    }
}
