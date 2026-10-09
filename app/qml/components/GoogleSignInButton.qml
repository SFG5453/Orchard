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

Rectangle {
    id: root

    property string text: qsTr("Continue with Google")

    signal clicked

    width: 330
    height: 48
    radius: 14
    color: !root.enabled ? "#71767e" : (mouseArea.containsPress ? "#e2e5e9" : (mouseArea.containsMouse ? "#f4f5f7" : "#ffffff"))
    scale: mouseArea.containsPress ? 0.985 : (mouseArea.containsMouse ? 1.008 : 1)
    // Keyboard navigation support
    focus: true
    Keys.onReturnPressed: {
        if (root.enabled) {
            root.clicked();
        }
    }
    Keys.onEnterPressed: {
        if (root.enabled) {
            root.clicked();
        }
    }
    Keys.onSpacePressed: {
        if (root.enabled) {
            root.clicked();
        }
    }

    Text {
        anchors.centerIn: parent
        text: root.text
        color: "#12151a"
        font.pixelSize: 15
        font.weight: Font.DemiBold
        font.family: "Inter"
    }

    MouseArea {
        id: mouseArea

        anchors.fill: parent
        hoverEnabled: root.enabled
        cursorShape: root.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
        enabled: root.enabled
        onClicked: root.clicked()
    }

    Behavior on color {
        ColorAnimation {
            duration: 120
        }
    }

    Behavior on scale {
        NumberAnimation {
            duration: 100
            easing.type: Easing.OutQuad
        }
    }
}
