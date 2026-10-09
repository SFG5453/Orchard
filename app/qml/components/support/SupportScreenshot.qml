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
import QtQuick.Layouts
import ".."
import "../docs"

// Screenshot slot. Three states: attached, suggested (the screen you were on
// when the popup opened, never sent unless you attach it), or empty.
Rectangle {
    id: root

    signal captureRequested
    signal chooseRequested

    readonly property bool attached: OrchardSupport.screenshotUrl.length > 0
    readonly property bool suggested: !attached && OrchardSupport.snapshotUrl.length > 0

    implicitHeight: content.implicitHeight + 24
    radius: 12
    color: "#141815"
    border.color: root.attached ? "#556f9a80" : "#2a302b"

    RowLayout {
        id: content
        anchors.fill: parent
        anchors.margins: 12
        spacing: 14

        Rectangle {
            Layout.preferredWidth: 176
            Layout.preferredHeight: 110
            visible: root.attached || root.suggested
            radius: 8
            color: "#0b0e0c"
            clip: true

            Image {
                anchors.fill: parent
                anchors.margins: 2
                source: root.attached ? OrchardSupport.screenshotUrl : OrchardSupport.snapshotUrl
                sourceSize.width: 352
                fillMode: Image.PreserveAspectFit
                // The provider reads live C++ state; keep it on the GUI thread and uncached.
                asynchronous: false
                cache: false
                opacity: root.attached ? 1 : 0.55
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 6

            Text {
                Layout.fillWidth: true
                text: root.attached ? qsTr("Screenshot attached")
                    : root.suggested ? qsTr("The screen you were on")
                    : qsTr("Screenshot")
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 13
                font.weight: Font.Medium
            }

            Text {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                text: root.attached
                    ? qsTr("%1. Check it for anything private before sending: it is posted publicly.").arg(OrchardSupport.screenshotInfo)
                    : root.suggested
                        ? qsTr("Captured when you opened this window. It is only sent if you attach it.")
                        : qsTr("Capture hides this window so you can open the screen with the problem first.")
                color: "#a4aaa1"
                font.family: "Inter"
                font.pixelSize: 11
            }

            Flow {
                Layout.fillWidth: true
                spacing: 6

                DocsPillButton {
                    visible: root.suggested
                    iconName: "check"
                    text: qsTr("Attach this screen")
                    onClicked: OrchardSupport.attachSnapshot()
                }

                DocsPillButton {
                    iconName: "camera"
                    text: root.attached || root.suggested ? qsTr("Capture another screen") : qsTr("Capture a screen")
                    onClicked: root.captureRequested()
                }

                DocsPillButton {
                    iconName: "image"
                    text: qsTr("Choose image...")
                    onClicked: root.chooseRequested()
                }

                DocsPillButton {
                    visible: root.attached
                    iconName: "trash-2"
                    text: qsTr("Remove")
                    onClicked: OrchardSupport.clearScreenshot()
                }
            }
        }
    }
}
