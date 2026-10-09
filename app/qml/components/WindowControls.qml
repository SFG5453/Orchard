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

Row {
    id: root

    required property Window targetWindow
    // Fade out after the pointer goes idle; wake() brings them back.
    property bool autoHide: false
    property bool awake: true
    readonly property bool hovered: minArea.containsMouse || maxArea.containsMouse || closeArea.containsMouse
    readonly property real shown: !autoHide || awake || hovered ? 1 : 0

    function wake() {
        awake = true;
        idleTimer.restart();
    }

    spacing: 0
    height: 38
    opacity: reveal
    // Invisible buttons shouldn't still close the app. Ghosts don't get a close button.
    visible: reveal > 0.01
    transform: Translate { y: -10 * (1 - root.reveal) }
    onAutoHideChanged: wake()

    property real reveal: shown
    Behavior on reveal {
        NumberAnimation { duration: 420; easing.type: Easing.OutCubic }
    }

    Timer {
        id: idleTimer
        interval: 2200
        onTriggered: root.awake = false
    }

    // Minimize Button
    Rectangle {
        width: 44
        height: parent.height
        color: minArea.containsPress ? "#25282e" : (minArea.containsMouse ? "#1e2126" : "transparent")

        Item {
            anchors.centerIn: parent
            width: 11
            height: 1

            Rectangle {
                anchors.fill: parent
                color: minArea.containsMouse ? "#ffffff" : "#9ba1ad"
            }
        }

        MouseArea {
            id: minArea

            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: {
                if (root.targetWindow)
                    root.targetWindow.showMinimized();
            }
        }
    }

    // Maximize / Restore Button
    Rectangle {
        width: 44
        height: parent.height
        color: maxArea.containsPress ? "#25282e" : (maxArea.containsMouse ? "#1e2126" : "transparent")

        Item {
            anchors.centerIn: parent
            width: 10
            height: 10

            Rectangle {
                anchors.fill: parent
                color: "transparent"
                border.color: maxArea.containsMouse ? "#ffffff" : "#9ba1ad"
                border.width: 1
            }
        }

        MouseArea {
            id: maxArea

            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: {
                if (!root.targetWindow)
                    return;

                if (root.targetWindow.visibility === Window.Maximized)
                    root.targetWindow.showNormal();
                else
                    root.targetWindow.showMaximized();
            }
        }
    }

    // Close Button
    Rectangle {
        width: 44
        height: parent.height
        color: closeArea.containsPress ? "#dc2626" : (closeArea.containsMouse ? "#e81123" : "transparent")

        Item {
            anchors.centerIn: parent
            width: 10
            height: 10

            Rectangle {
                width: 12
                height: 1.2
                color: closeArea.containsMouse ? "#ffffff" : "#9ba1ad"
                anchors.centerIn: parent
                rotation: 45
            }

            Rectangle {
                width: 12
                height: 1.2
                color: closeArea.containsMouse ? "#ffffff" : "#9ba1ad"
                anchors.centerIn: parent
                rotation: -45
            }
        }

        MouseArea {
            id: closeArea

            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: {
                if (root.targetWindow)
                    root.targetWindow.close();
            }
        }
    }
}
