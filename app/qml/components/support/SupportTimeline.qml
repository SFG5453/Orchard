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
import "../docs"

// One report's GitHub activity: replies, commits that mention it, closes and reopens.
Item {
    id: root

    signal closeRequested

    // Untyped helpers keep QVariantMap reads out of compiled bindings.
    function report() {
        return OrchardSupport.activeReport || {};
    }

    function field(name) {
        const value = root.report()[name];
        return value === undefined ? "" : value;
    }

    function stateText() {
        if (root.field("state") !== "closed")
            return qsTr("Open");
        const reason = root.field("state_reason");
        if (reason === "not_planned")
            return qsTr("Closed as not planned");
        if (reason === "duplicate")
            return qsTr("Closed as a duplicate");
        return qsTr("Closed");
    }

    function since(seconds) {
        const delta = Math.max(0, Date.now() / 1000 - seconds);
        if (delta < 60)
            return qsTr("just now");
        if (delta < 3600)
            return qsTr("%1 min ago").arg(Math.floor(delta / 60));
        if (delta < 86400)
            return qsTr("%1 h ago").arg(Math.floor(delta / 3600));
        return Qt.formatDate(new Date(seconds * 1000), Locale.ShortFormat);
    }

    function icon(kind) {
        switch (kind) {
        case "comment": return "message-circle";
        case "commit": return "git-commit-horizontal";
        case "pull_request":
        case "mention": return "share-2";
        case "closed": return "check";
        case "reopened": return "refresh-cw";
        case "assigned": return "circle-user-round";
        default: return "info";
        }
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.topMargin: 22
        anchors.leftMargin: 28
        anchors.rightMargin: 18
        spacing: 12

        RowLayout {
            Layout.fillWidth: true
            spacing: 12

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 4
                Text {
                    Layout.fillWidth: true
                    text: root.field("title")
                    elide: Text.ElideRight
                    color: "#f0eee7"
                    font.family: "Inter"
                    font.pixelSize: 20
                    font.weight: Font.DemiBold
                }
                Text {
                    Layout.fillWidth: true
                    text: qsTr("#%1 · %2").arg(root.field("number")).arg(root.stateText())
                    color: root.field("state") === "closed" ? "#c4e0cb" : "#a4aaa1"
                    font.family: "Inter"
                    font.pixelSize: 12
                }
            }

            DocsPillButton {
                iconName: "github"
                text: qsTr("Open on GitHub")
                enabled: root.field("url") !== ""
                onClicked: Qt.openUrlExternally(root.field("url"))
            }

            DocsPillButton {
                iconName: "x"
                text: qsTr("Close")
                onClicked: root.closeRequested()
            }
        }

        Text {
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            text: qsTr("Reply on GitHub. Orchard checks for news every few minutes and tells you when something happens.")
            color: "#7c857f"
            font.family: "Inter"
            font.pixelSize: 11
        }

        ListView {
            id: events
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.rightMargin: 10
            clip: true
            spacing: 2
            model: OrchardSupport.activeEvents
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar {}
            // Newest at the bottom, like the issue page; open at the latest news.
            onCountChanged: Qt.callLater(positionViewAtEnd)

            delegate: ItemDelegate {
                id: row
                required property var modelData

                width: ListView.view.width
                leftPadding: 4
                rightPadding: 8
                topPadding: 10
                bottomPadding: 10
                hoverEnabled: true
                Accessible.name: modelData.title
                onClicked: Qt.openUrlExternally(modelData.url)

                contentItem: RowLayout {
                    spacing: 12

                    Rectangle {
                        Layout.alignment: Qt.AlignTop
                        Layout.preferredWidth: 30
                        Layout.preferredHeight: 30
                        radius: 15
                        color: "#22302a"
                        clip: true

                        LucideIcon {
                            anchors.centerIn: parent
                            width: 15
                            height: 15
                            name: root.icon(row.modelData.kind)
                            color: "#c4e0cb"
                        }

                        Image {
                            anchors.fill: parent
                            visible: row.modelData.kind === "comment" && status === Image.Ready
                            source: row.modelData.kind === "comment" ? row.modelData.actor_avatar : ""
                            sourceSize: Qt.size(60, 60)
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 4

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8
                            Text {
                                text: row.modelData.title
                                color: "#f0eee7"
                                font.family: "Inter"
                                font.pixelSize: 13
                                font.weight: Font.Medium
                            }
                            Rectangle {
                                visible: row.modelData.maintainer
                                implicitWidth: badge.implicitWidth + 12
                                implicitHeight: 18
                                radius: 9
                                color: "#2244604f"
                                border.color: "#556f9a80"
                                Text {
                                    id: badge
                                    anchors.centerIn: parent
                                    text: qsTr("Maintainer")
                                    color: "#c4e0cb"
                                    font.family: "Inter"
                                    font.pixelSize: 10
                                }
                            }
                            Item { Layout.fillWidth: true }
                            Text {
                                text: root.since(row.modelData.created_at)
                                color: "#7c857f"
                                font.family: "Inter"
                                font.pixelSize: 11
                            }
                        }

                        Text {
                            Layout.fillWidth: true
                            visible: text.length > 0
                            text: row.modelData.body
                            textFormat: Text.PlainText
                            wrapMode: Text.Wrap
                            maximumLineCount: 12
                            elide: Text.ElideRight
                            color: "#c9ccc4"
                            font.family: "Inter"
                            font.pixelSize: 12
                            lineHeight: 1.2
                        }
                    }
                }
                background: Rectangle {
                    radius: 10
                    color: row.hovered ? "#0cffffff" : "transparent"
                }
            }

            Text {
                anchors.centerIn: parent
                width: Math.min(380, parent.width - 24)
                visible: events.count === 0 && !OrchardSupport.activeLoading
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: qsTr("No activity yet. Replies, commits that mention this report, and its closing show up here.")
                color: "#7c857f"
                font.family: "Inter"
                font.pixelSize: 12
            }

            BusyIndicator {
                anchors.centerIn: parent
                running: OrchardSupport.activeLoading && events.count === 0
                visible: running
            }
        }
    }
}
