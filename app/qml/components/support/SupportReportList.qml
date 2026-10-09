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

pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."

// Your reports, newest activity first, with a dot where something happened.
Item {
    id: root

    property string currentId: ""
    property bool composing: false

    signal reportSelected(string id)
    signal composeRequested

    function stateLabel(report) {
        if (report.state !== "closed")
            return qsTr("Open");
        if (report.state_reason === "not_planned")
            return qsTr("Not planned");
        if (report.state_reason === "duplicate")
            return qsTr("Duplicate");
        return qsTr("Closed");
    }

    function subtitle(report) {
        const latest = report.latest;
        const prefix = qsTr("#%1 · %2").arg(report.number).arg(root.stateLabel(report));
        return latest && latest.title ? prefix + " · " + latest.title : prefix;
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 18
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            Text {
                Layout.fillWidth: true
                text: qsTr("Bug reports")
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 17
                font.weight: Font.DemiBold
            }

            BusyIndicator {
                Layout.preferredWidth: 18
                Layout.preferredHeight: 18
                running: OrchardSupport.loading
                visible: running
            }
        }

        ItemDelegate {
            id: newButton
            Layout.fillWidth: true
            implicitHeight: 42
            leftPadding: 12
            hoverEnabled: true
            Accessible.name: qsTr("New report")
            onClicked: root.composeRequested()

            contentItem: RowLayout {
                spacing: 10
                LucideIcon {
                    Layout.preferredWidth: 16
                    Layout.preferredHeight: 16
                    name: "plus"
                    color: "#c4e0cb"
                }
                Text {
                    Layout.fillWidth: true
                    text: qsTr("New report")
                    color: "#f0eee7"
                    font.family: "Inter"
                    font.pixelSize: 14
                }
            }
            background: Rectangle {
                radius: 12
                color: root.composing ? "#2244604f" : newButton.hovered ? "#14ffffff" : "transparent"
                border.color: root.composing ? "#556f9a80" : newButton.visualFocus ? "#a6d4bf" : "#1cffffff"
                Behavior on color { ColorAnimation { duration: 120 } }
            }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 4
            model: OrchardSupport.reports
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar {}

            delegate: ItemDelegate {
                id: row
                required property var modelData
                readonly property bool current: modelData.id === root.currentId

                width: ListView.view.width
                implicitHeight: 62
                leftPadding: 12
                rightPadding: 12
                hoverEnabled: true
                Accessible.name: modelData.title
                onClicked: root.reportSelected(modelData.id)

                contentItem: RowLayout {
                    spacing: 10

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 3
                        Text {
                            Layout.fillWidth: true
                            text: row.modelData.title
                            elide: Text.ElideRight
                            color: "#f0eee7"
                            font.family: "Inter"
                            font.pixelSize: 13
                            font.weight: row.modelData.unread > 0 ? Font.DemiBold : Font.Normal
                        }
                        Text {
                            Layout.fillWidth: true
                            text: root.subtitle(row.modelData)
                            elide: Text.ElideRight
                            color: "#a4aaa1"
                            font.family: "Inter"
                            font.pixelSize: 11
                        }
                    }

                    // Something happened since you last looked.
                    Rectangle {
                        Layout.preferredWidth: 8
                        Layout.preferredHeight: 8
                        radius: 4
                        visible: row.modelData.unread > 0
                        color: "#8cc5a5"
                    }
                }
                background: Rectangle {
                    radius: 12
                    color: row.current ? "#1cffffff" : row.hovered ? "#10ffffff" : "transparent"
                    border.color: row.visualFocus ? "#a6d4bf" : "transparent"
                    Behavior on color { ColorAnimation { duration: 120 } }
                }
            }

            Text {
                anchors.centerIn: parent
                width: parent.width - 24
                visible: list.count === 0 && OrchardSupport.loaded
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: qsTr("No reports yet. Everything working is also a fine outcome.")
                color: "#7c857f"
                font.family: "Inter"
                font.pixelSize: 12
            }
        }

        Text {
            Layout.fillWidth: true
            text: qsTr("Linked as @%1").arg(OrchardSupport.githubLogin)
            elide: Text.ElideRight
            color: "#7c857f"
            font.family: "Inter"
            font.pixelSize: 11
        }
    }
}
