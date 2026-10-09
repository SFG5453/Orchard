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

import ".."
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Moon button with a duration menu for OrchardSleep.
Button {
    id: root

    property color accentColor: "#f0eee7"
    property color primaryText: "#f5f3ee"
    property color secondaryText: "#b4b8b1"
    property color mutedText: "#858a82"
    readonly property var presets: [5, 10, 15, 30, 45, 60]

    function remainingLabel() {
        if (OrchardSleep.endOfTrack)
            return qsTr("End of track");
        const total = OrchardSleep.remainingSeconds;
        const minutes = Math.floor(total / 60);
        return minutes + ":" + String(total % 60).padStart(2, "0");
    }

    objectName: "sleepTimerButton"
    leftPadding: 7
    rightPadding: OrchardSleep.active ? 10 : 7
    focusPolicy: Qt.TabFocus
    topPadding: 0
    bottomPadding: 0
    implicitWidth: leftPadding + contentItem.implicitWidth + rightPadding
    implicitHeight: 30
    Accessible.name: OrchardSleep.active ? qsTr("Sleep timer, %1").arg(remainingLabel()) : qsTr("Sleep timer")
    ToolTip.visible: hovered && !menu.visible
    ToolTip.delay: 600
    ToolTip.text: Accessible.name
    onClicked: menu.opened ? menu.close() : menu.open()

    background: Rectangle {
        radius: height / 2
        color: root.down ? "#26ffffff" : OrchardSleep.active ? Qt.rgba(root.accentColor.r, root.accentColor.g, root.accentColor.b, root.hovered ? 0.3 : 0.2) : root.hovered || root.activeFocus ? "#1affffff" : "transparent"
        border.color: root.activeFocus ? root.accentColor : "transparent"
        Behavior on color { ColorAnimation { duration: 120 } }
    }

    contentItem: RowLayout {
        spacing: 4

        LucideIcon {
            Layout.alignment: Qt.AlignVCenter
            Layout.preferredWidth: 16
            Layout.preferredHeight: 16
            name: "moon"
            color: OrchardSleep.active ? root.accentColor : root.hovered ? root.primaryText : root.secondaryText
        }

        Text {
            id: label
            visible: OrchardSleep.active
            text: root.remainingLabel()
            color: root.accentColor
            font.family: "Inter"
            font.pixelSize: 11
            font.bold: true
            font.features: { "tnum": 1 }
        }
    }

    Popup {
        id: menu
        y: root.height + 6
        x: root.width - width
        width: 176
        padding: 6
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent

        background: Rectangle {
            radius: 14
            color: "#f01a1d19"
            border.color: "#26ffffff"
        }

        contentItem: ColumnLayout {
            spacing: 2

            Text {
                Layout.leftMargin: 8
                Layout.topMargin: 4
                Layout.bottomMargin: 2
                text: qsTr("SLEEP TIMER")
                color: root.mutedText
                font.family: "Inter"
                font.pixelSize: 10
                font.bold: true
                font.letterSpacing: 0.9
            }

            Repeater {
                model: root.presets

                SleepOption {
                    required property int modelData
                    text: qsTr("%n minute(s)", "", modelData)
                    onTriggered: OrchardSleep.startMinutes(modelData)
                }
            }

            SleepOption {
                objectName: "sleepEndOfTrack"
                text: qsTr("End of this track")
                onTriggered: OrchardSleep.startEndOfTrack()
            }

            SleepOption {
                objectName: "sleepOff"
                visible: OrchardSleep.active
                text: qsTr("Turn off")
                danger: true
                onTriggered: OrchardSleep.cancel()
            }
        }
    }

    // Menu row.
    component SleepOption: Button {
        id: option
        property bool danger: false
        signal triggered()

        Layout.fillWidth: true
        implicitHeight: 30
        leftPadding: 10
        rightPadding: 10
        onClicked: {
            option.triggered();
            menu.close();
        }

        background: Rectangle {
            radius: 9
            color: option.down ? "#26ffffff" : option.hovered || option.activeFocus ? "#1affffff" : "transparent"
        }

        contentItem: Text {
            text: option.text
            color: option.danger ? "#e6a197" : root.primaryText
            font.family: "Inter"
            font.pixelSize: 12
            verticalAlignment: Text.AlignVCenter
        }
    }
}
