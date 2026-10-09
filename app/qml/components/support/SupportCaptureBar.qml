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
import ".."

// Floats over the app while the report popup is away. Hides itself before the
// shot, so the picture shows the bug and not this bar. Say cheese, bug.
Rectangle {
    id: root

    property bool active: false

    signal capture
    signal cancel

    implicitWidth: row.implicitWidth + 16
    implicitHeight: 52
    radius: height / 2
    color: "#e6141815"
    border.color: "#556f9a80"
    // No fade-out: the capture happens right after this flips.
    visible: root.active

    RowLayout {
        id: row
        anchors.centerIn: parent
        spacing: 10

        LucideIcon {
            Layout.leftMargin: 10
            Layout.preferredWidth: 18
            Layout.preferredHeight: 18
            name: "camera"
            color: "#c4e0cb"
        }

        Text {
            text: qsTr("Open the screen with the problem, then capture.")
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 13
        }

        Button {
            id: captureButton
            implicitHeight: 36
            leftPadding: 16
            rightPadding: 16
            text: qsTr("Capture")
            Accessible.name: qsTr("Capture this screen")
            onClicked: root.capture()
            contentItem: Text {
                text: captureButton.text
                color: "#0d120f"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: Font.DemiBold
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }
            background: Rectangle {
                radius: height / 2
                color: captureButton.down ? "#9ccfb1" : captureButton.hovered ? "#b5e0c6" : "#a6d4bf"
            }
        }

        Button {
            id: cancelButton
            implicitHeight: 36
            text: qsTr("Cancel")
            onClicked: root.cancel()
            contentItem: Text {
                text: cancelButton.text
                color: "#d6d9d3"
                font.family: "Inter"
                font.pixelSize: 13
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }
            background: Rectangle {
                radius: height / 2
                color: cancelButton.hovered ? "#1cffffff" : "transparent"
            }
        }
    }
}
