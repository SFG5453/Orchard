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

Rectangle {
    id: root

    default property alias pages: stack.data
    property alias stack: stack
    property alias pageScroller: pageScroller
    property alias backButton: backButton
    property alias skipButton: skipButton
    property alias nextButton: nextButton
    property alias dashRepeater: dashRepeater

    color: "#0d0f12"

    // Swallows input so the shell underneath stays inert.
    MouseArea {
        anchors.fill: parent
    }

    Item {
        anchors.fill: parent
        anchors.topMargin: 38
        anchors.bottomMargin: 36

        Flickable {
            id: pageScroller

            anchors.top: parent.top
            anchors.bottom: navigation.top
            anchors.bottomMargin: 24
            anchors.left: parent.left
            anchors.right: parent.right
            contentWidth: width
            contentHeight: Math.max(height, stack.implicitHeight)
            boundsBehavior: Flickable.StopAtBounds
            clip: true

            StackLayout {
                id: stack

                x: (pageScroller.width - width) / 2
                y: Math.max(0, (pageScroller.height - height) / 2)
                width: Math.min(640, pageScroller.width)
                height: implicitHeight
            }

            ScrollBar.vertical: ScrollBar {}
        }

        RowLayout {
            id: navigation

            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            width: 840
            spacing: 16

            RowLayout {
                Layout.fillWidth: true
                Layout.preferredWidth: 1
                spacing: 8

                WelcomeButton {
                    id: backButton

                    text: qsTr("Back")
                }

                WelcomeButton {
                    id: skipButton

                    text: qsTr("Skip setup")
                    flat: true
                }

                Item {
                    Layout.fillWidth: true
                }
            }

            Row {
                id: dashes

                spacing: 8

                Repeater {
                    id: dashRepeater
                }
            }

            RowLayout {
                Layout.fillWidth: true
                Layout.preferredWidth: 1

                Item {
                    Layout.fillWidth: true
                }

                WelcomeButton {
                    id: nextButton

                    primary: true
                }
            }
        }
    }
}
