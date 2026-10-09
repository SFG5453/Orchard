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

// Page list grouped by topic, a search box on top and copy-everything at the bottom.
// Answers to a typed question replace the page list while there are any.
ColumnLayout {
    id: root

    property var pages: []
    // [{pageId, pageTitle, icon, section, snippet}] from the docs assistant, best first.
    property var answers: []
    // The assistant is looking for answers.
    property bool asking: false
    // The docs assistant is running, so the box takes questions.
    property bool askable: false
    property string currentId
    property string filter: ""
    property bool copiedAll: false
    // pageId and section of the answer last opened, so its row stays marked.
    property string openedAnswer: ""
    // Enter was pressed while answers were still on their way.
    property bool openWhenReady: false

    signal pageSelected(string id)
    signal answerSelected(var answer)
    signal filterEdited(string text)
    signal copyAllRequested

    onAnswersChanged: root.openedAnswer = ""
    onAskingChanged: {
        if (!root.asking && root.openWhenReady) {
            root.openWhenReady = false;
            root.openFirst();
        }
    }

    // Flat rows: one "Best matches" label over the answers, or a group label whenever the group changes over its pages.
    readonly property var rows: {
        const out = [];
        if (root.answers.length > 0) {
            out.push({ kind: "group", label: qsTr("Best matches") });
            for (const answer of root.answers)
                out.push({ kind: "answer", answer: answer });
            return out;
        }
        let group = "";
        for (const page of root.pages) {
            if (page.group !== group) {
                group = page.group;
                out.push({ kind: "group", label: group });
            }
            out.push({ kind: "page", page: page });
        }
        return out;
    }

    function focusSearch() {
        search.forceActiveFocus();
    }

    function answerKey(answer) {
        return answer.pageId + "#" + answer.section;
    }

    // The best answer, or the first page when there are none.
    function openFirst() {
        if (root.answers.length > 0) {
            root.openedAnswer = root.answerKey(root.answers[0]);
            root.answerSelected(root.answers[0]);
        } else if (root.pages.length > 0) {
            root.pageSelected(root.pages[0].id);
        }
    }

    onCurrentIdChanged: {
        const index = root.rows.findIndex(row => row.kind === "page" && row.page.id === root.currentId);
        if (index === 1)
            list.positionViewAtBeginning();
        else if (index >= 0)
            list.positionViewAtIndex(index, ListView.Contain);
    }

    spacing: 0

    Text {
        text: qsTr("Docs")
        color: "#8d968e"
        font.family: "Inter"
        font.pixelSize: 12
        font.weight: Font.DemiBold
        font.letterSpacing: 0.6
        font.capitalization: Font.AllUppercase
        Layout.leftMargin: 32
        Layout.topMargin: 26
        Layout.bottomMargin: 12
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.leftMargin: 20
        Layout.rightMargin: 16
        Layout.preferredHeight: 40
        radius: 10
        color: search.activeFocus ? "#1affffff" : "#10ffffff"
        border.color: search.activeFocus ? "#8cc5a5" : "#1cffffff"
        Behavior on color { ColorAnimation { duration: 140 } }
        Behavior on border.color { ColorAnimation { duration: 140 } }

        LucideIcon {
            x: 13
            anchors.verticalCenter: parent.verticalCenter
            width: 15
            height: 15
            name: "search"
            color: "#a4aaa1"
        }
        TextField {
            id: search
            anchors.fill: parent
            leftPadding: 38
            rightPadding: 36
            placeholderText: root.askable ? qsTr("Search or ask a question") : qsTr("Search the docs")
            placeholderTextColor: "#7c857f"
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 13
            background: Item {}
            Accessible.name: placeholderText
            onTextEdited: {
                root.openWhenReady = false;
                root.filterEdited(text);
            }
            onAccepted: {
                if (root.asking)
                    root.openWhenReady = true;
                else
                    root.openFirst();
            }
        }
        Button {
            id: clearButton
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.rightMargin: 5
            width: 28
            height: 28
            visible: search.text.length > 0
            hoverEnabled: true
            Accessible.name: qsTr("Clear search")
            onClicked: {
                search.clear();
                root.filterEdited("");
                search.forceActiveFocus();
            }
            contentItem: LucideIcon {
                name: "x"
                color: "#d6d9d3"
            }
            background: Rectangle {
                radius: 14
                color: clearButton.hovered ? "#20ffffff" : "transparent"
            }
        }
    }

    // Sweeps while the assistant looks for answers.
    Item {
        Layout.fillWidth: true
        Layout.leftMargin: 28
        Layout.rightMargin: 24
        Layout.topMargin: 3
        Layout.preferredHeight: 2
        clip: true
        opacity: root.asking ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 160 } }

        Rectangle {
            anchors.fill: parent
            radius: 1
            color: "#14ffffff"
        }
        Rectangle {
            id: sweep
            width: parent.width * 0.3
            height: parent.height
            radius: 1
            color: "#8cc5a5"
            NumberAnimation on x {
                running: root.asking
                from: -sweep.width
                to: sweep.parent.width
                duration: 900
                loops: Animation.Infinite
            }
        }
    }

    ListView {
        id: list
        Layout.fillWidth: true
        Layout.fillHeight: true
        Layout.topMargin: 5
        leftMargin: 20
        rightMargin: 16
        bottomMargin: 8
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        model: root.rows
        currentIndex: root.rows.findIndex(row => row.kind === "answer" ? root.openedAnswer === root.answerKey(row.answer)
            : row.kind === "page" && row.page.id === root.currentId)
        highlightMoveDuration: 260
        highlightMoveVelocity: -1
        highlightResizeDuration: 0
        highlightFollowsCurrentItem: true
        // One pill that glides between pages, same as the settings nav. Zamboni not included.
        highlight: Item {
            Rectangle {
                anchors.fill: parent
                anchors.topMargin: 1
                anchors.bottomMargin: 1
                radius: 10
                gradient: Gradient {
                    GradientStop { position: 0.0; color: "#338cc4a0" }
                    GradientStop { position: 1.0; color: "#1a8cc4a0" }
                }
                border.color: "#38a0d6b4"
            }
        }
        ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

        delegate: Item {
            id: row
            required property var modelData
            readonly property bool isGroup: row.modelData.kind === "group"
            readonly property bool isAnswer: row.modelData.kind === "answer"
            readonly property var entry: row.isGroup ? null : (row.isAnswer ? row.modelData.answer : row.modelData.page)
            // An answer names its section; a whole-page answer names the page.
            readonly property string label: row.isGroup ? row.modelData.label
                : row.isAnswer ? (row.entry.section !== "" ? row.entry.section : row.entry.pageTitle) : row.entry.title
            readonly property string detail: row.isAnswer ? (row.entry.section !== "" ? row.entry.pageTitle : row.entry.snippet) : ""

            width: ListView.view.width - ListView.view.leftMargin - ListView.view.rightMargin
            height: row.isGroup ? 36 : row.isAnswer ? 58 : 40

            Text {
                visible: row.isGroup
                anchors.left: parent.left
                anchors.leftMargin: 12
                anchors.bottom: parent.bottom
                anchors.bottomMargin: 6
                text: row.isGroup ? row.label : ""
                color: "#8d968e"
                font.family: "Inter"
                font.pixelSize: 12
                font.weight: Font.DemiBold
                font.letterSpacing: 0.9
                font.capitalization: Font.AllUppercase
            }

            ItemDelegate {
                id: item
                readonly property bool current: row.isAnswer ? root.openedAnswer === root.answerKey(row.entry)
                    : !row.isGroup && row.entry.id === root.currentId
                visible: !row.isGroup
                anchors.fill: parent
                anchors.topMargin: 1
                anchors.bottomMargin: 1
                leftPadding: 12
                rightPadding: 12
                hoverEnabled: true
                Accessible.name: row.isGroup ? "" : (row.detail !== "" ? row.label + ", " + row.detail : row.label)
                onClicked: {
                    if (row.isAnswer) {
                        root.openedAnswer = root.answerKey(row.entry);
                        root.answerSelected(row.entry);
                    } else {
                        root.pageSelected(row.entry.id);
                    }
                }

                contentItem: RowLayout {
                    spacing: 12
                    LucideIcon {
                        Layout.preferredWidth: 17
                        Layout.preferredHeight: 17
                        name: row.isGroup ? "" : row.entry.icon
                        color: item.current ? "#c4e0cb" : "#a4aaa1"
                        scale: item.current ? 1.1 : 1.0
                        Behavior on color { ColorAnimation { duration: 140 } }
                        Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.OutBack } }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            Layout.fillWidth: true
                            text: row.isGroup ? "" : row.label
                            elide: Text.ElideRight
                            color: item.current ? "#f0eee7" : "#c9ccc4"
                            font.family: "Inter"
                            font.pixelSize: 14
                            font.weight: item.current ? Font.DemiBold : Font.Normal
                            Behavior on color { ColorAnimation { duration: 140 } }
                        }
                        Text {
                            Layout.fillWidth: true
                            visible: row.isAnswer
                            text: row.detail
                            elide: Text.ElideRight
                            color: "#8d968e"
                            font.family: "Inter"
                            font.pixelSize: 11
                        }
                    }
                }
                background: Rectangle {
                    radius: 10
                    // Hover tint only; the highlight pill sits underneath.
                    color: item.current ? "transparent" : item.down ? "#22ffffff" : item.hovered ? "#14ffffff" : "transparent"
                    border.color: item.visualFocus ? "#a6d4bf" : "transparent"
                    Behavior on color { ColorAnimation { duration: 120 } }
                }
            }
        }

        Text {
            anchors.centerIn: parent
            width: parent.width - 48
            visible: root.rows.length === 0
            text: root.asking ? qsTr("Finding answers…") : qsTr("No pages match “%1”").arg(root.filter.trim())
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            color: "#8d968e"
            font.family: "Inter"
            font.pixelSize: 13
        }
    }

    DocsPillButton {
        Layout.fillWidth: true
        Layout.leftMargin: 20
        Layout.rightMargin: 16
        Layout.topMargin: 4
        Layout.bottomMargin: 20
        iconName: "copy"
        done: root.copiedAll
        text: root.copiedAll ? qsTr("Copied all docs") : qsTr("Copy all docs for AI")
        Accessible.name: qsTr("Copy every page as one Markdown document")
        onClicked: root.copyAllRequested()
    }
}
