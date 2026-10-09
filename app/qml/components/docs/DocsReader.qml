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

// The page itself: header, one panel per section, related pages, previous and next.
ColumnLayout {
    id: root

    property var page: null
    property var sections: []
    property var related: []
    property var previousPage: null
    property var nextPage: null
    property bool copied: false
    // Section to bring into view; focusSerial changes on every request, even for the same section.
    property string focusSection: ""
    property int focusSerial: 0

    signal pageRequested(string id)
    signal copyRequested
    signal closeRequested

    onPageChanged: {
        scroller.contentItem.contentY = 0;
        headerFade.restart();
    }
    onFocusSerialChanged: focusDelay.restart()

    // Text before the first heading sits under the header; every later section is a row of the page panel.
    readonly property var introBlocks: root.sections.length > 0 && root.sections[0].title === "" ? root.sections[0].blocks : []
    readonly property var rowSections: root.sections.filter(entry => entry.title !== "")
    // The scroll area's Flickable, untyped because Qt exposes it as a plain item.
    readonly property var flickable: scroller.contentItem
    function rowAt(index: int): var {
        return rowRepeater.itemAt(index);
    }

    function scrollToSection() {
        if (root.focusSection === "")
            return;
        for (let i = 0; i < root.rowSections.length; ++i) {
            if (root.rowSections[i].title !== root.focusSection)
                continue;
            const card = root.rowAt(i);
            if (!card)
                return;
            const flick = root.flickable;
            const top = card.mapToItem(flick.contentItem, 0, 0).y - 24;
            scrollAnimation.to = Math.max(0, Math.min(top, flick.contentHeight - flick.height));
            scrollAnimation.restart();
            card.flash();
            return;
        }
    }

    // The new page needs a moment to lay out before a section has a position.
    Timer {
        id: focusDelay
        interval: 80
        onTriggered: root.scrollToSection()
    }

    NumberAnimation {
        id: scrollAnimation
        target: scroller.contentItem
        property: "contentY"
        duration: 320
        easing.type: Easing.OutCubic
    }

    spacing: 0

    // Same header shape as the settings popup: title, one-line blurb, actions on the right.
    RowLayout {
        Layout.fillWidth: true
        Layout.topMargin: 26
        Layout.leftMargin: 36
        Layout.rightMargin: 20
        Layout.bottomMargin: 18
        spacing: 16

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 6

            NumberAnimation on opacity {
                id: headerFade
                from: 0
                to: 1
                duration: 220
                easing.type: Easing.OutCubic
            }
            Text {
                Layout.fillWidth: true
                text: root.page ? root.page.title : qsTr("Docs unavailable")
                wrapMode: Text.WordWrap
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 26
                font.weight: Font.DemiBold
            }
            Text {
                Layout.fillWidth: true
                text: root.page ? root.page.summary : qsTr("This build has no documentation pages.")
                wrapMode: Text.WordWrap
                color: "#a3ada5"
                font.family: "Inter"
                font.pixelSize: 13
            }
        }

        DocsPillButton {
            Layout.alignment: Qt.AlignTop
            iconName: "copy"
            done: root.copied
            text: root.copied ? qsTr("Copied") : qsTr("Copy page")
            enabled: root.page !== null
            Accessible.name: qsTr("Copy this page as Markdown")
            onClicked: root.copyRequested()
        }

        RoundButton {
            id: closeButton
            Layout.alignment: Qt.AlignTop
            implicitWidth: 34
            implicitHeight: 34
            hoverEnabled: true
            Accessible.name: qsTr("Close docs")
            onClicked: root.closeRequested()
            contentItem: Item {
                LucideIcon {
                    anchors.centerIn: parent
                    width: 16
                    height: 16
                    name: "x"
                    color: "#f0eee7"
                }
            }
            background: Rectangle {
                radius: width / 2
                color: closeButton.down ? "#30ffffff" : closeButton.hovered ? "#20ffffff" : "#10ffffff"
                border.color: closeButton.visualFocus ? "#a6d4bf" : "transparent"
            }
        }
    }

    ScrollView {
        id: scroller
        Layout.fillWidth: true
        Layout.fillHeight: true
        contentWidth: availableWidth
        clip: true

        Item {
            width: scroller.availableWidth
            implicitHeight: column.implicitHeight + 36

            ColumnLayout {
                id: column
                x: 36
                // Right inset keeps the scrollbar clear of the panels; the cap keeps lines readable.
                width: Math.min(760, parent.width - 36 - 28)
                spacing: 22

                ColumnLayout {
                    Layout.fillWidth: true
                    visible: root.introBlocks.length > 0
                    spacing: 12

                    Repeater {
                        model: root.introBlocks
                        delegate: DocsBlock {
                            required property var modelData
                            Layout.fillWidth: true
                            block: modelData
                        }
                    }
                }

                // One panel for the whole page; sections are rows split by hairlines.
                Rectangle {
                    Layout.fillWidth: true
                    visible: root.rowSections.length > 0
                    implicitHeight: rows.implicitHeight
                    radius: 16
                    color: "#0affffff"
                    border.color: "#1cffffff"

                    ColumnLayout {
                        id: rows
                        width: parent.width
                        spacing: 0

                        Repeater {
                            id: rowRepeater
                            model: root.rowSections

                            delegate: DocsCard {
                                id: row
                                required property var modelData
                                required property int index
                                title: row.modelData.title
                                order: row.index

                                Repeater {
                                    model: row.modelData.blocks
                                    delegate: DocsBlock {
                                        required property var modelData
                                        Layout.fillWidth: true
                                        block: modelData
                                    }
                                }
                            }
                        }
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    visible: root.related.length > 0
                    spacing: 10

                    Text {
                        Layout.leftMargin: 4
                        text: qsTr("Related pages")
                        color: "#8d968e"
                        font.family: "Inter"
                        font.pixelSize: 12
                        font.weight: Font.DemiBold
                        font.letterSpacing: 0.9
                        font.capitalization: Font.AllUppercase
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: relatedColumn.implicitHeight
                        radius: 16
                        color: "#0affffff"
                        border.color: "#1cffffff"

                        Column {
                            id: relatedColumn
                            width: parent.width

                            Repeater {
                                model: root.related

                                delegate: Button {
                                    id: link
                                    required property var modelData
                                    required property int index
                                    width: relatedColumn.width
                                    implicitHeight: 52
                                    leftPadding: 20
                                    rightPadding: 20
                                    hoverEnabled: true
                                    Accessible.name: link.modelData.title
                                    onClicked: root.pageRequested(link.modelData.id)

                                    contentItem: RowLayout {
                                        spacing: 14
                                        LucideIcon {
                                            Layout.preferredWidth: 17
                                            Layout.preferredHeight: 17
                                            name: link.modelData.icon
                                            color: "#a8cdb6"
                                        }
                                        Text {
                                            Layout.fillWidth: true
                                            text: link.modelData.title
                                            elide: Text.ElideRight
                                            color: "#f0eee7"
                                            font.family: "Inter"
                                            font.pixelSize: 14
                                        }
                                        LucideIcon {
                                            Layout.preferredWidth: 14
                                            Layout.preferredHeight: 14
                                            name: "chevron-right"
                                            color: "#7c857f"
                                        }
                                    }
                                    background: Item {
                                        Rectangle {
                                            anchors.fill: parent
                                            anchors.margins: 1
                                            // Inset hover tint keeps the panel's rounded corners clean.
                                            radius: 14
                                            color: link.down ? "#22ffffff" : link.hovered ? "#12ffffff" : "transparent"
                                            border.color: link.visualFocus ? "#a6d4bf" : "transparent"
                                            Behavior on color { ColorAnimation { duration: 120 } }
                                        }
                                        Rectangle {
                                            visible: link.index > 0
                                            width: parent.width
                                            height: 1
                                            color: "#12ffffff"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Previous and next share one panel, split by a hairline.
                Rectangle {
                    Layout.fillWidth: true
                    visible: root.previousPage !== null || root.nextPage !== null
                    implicitHeight: 68
                    radius: 16
                    color: "#0affffff"
                    border.color: "#1cffffff"

                    RowLayout {
                        anchors.fill: parent
                        spacing: 0

                        Repeater {
                            model: [
                                { direction: "previous", target: root.previousPage },
                                { direction: "next", target: root.nextPage }
                            ]

                            delegate: Button {
                                id: nav
                                required property var modelData
                                required property int index
                                readonly property bool isNext: nav.modelData.direction === "next"
                                Layout.fillWidth: true
                                Layout.preferredWidth: 1
                                Layout.fillHeight: true
                                visible: nav.modelData.target !== null
                                hoverEnabled: true
                                leftPadding: 20
                                rightPadding: 20
                                onClicked: root.pageRequested(nav.modelData.target.id)

                                contentItem: RowLayout {
                                    spacing: 12
                                    layoutDirection: nav.isNext ? Qt.RightToLeft : Qt.LeftToRight
                                    LucideIcon {
                                        Layout.preferredWidth: 18
                                        Layout.preferredHeight: 18
                                        name: nav.isNext ? "chevron-right" : "chevron-left"
                                        color: "#a8cdb6"
                                    }
                                    ColumnLayout {
                                        Layout.fillWidth: true
                                        spacing: 2
                                        Text {
                                            Layout.fillWidth: true
                                            text: nav.isNext ? qsTr("Next") : qsTr("Previous")
                                            horizontalAlignment: nav.isNext ? Text.AlignRight : Text.AlignLeft
                                            color: "#8d968e"
                                            font.family: "Inter"
                                            font.pixelSize: 11
                                        }
                                        Text {
                                            Layout.fillWidth: true
                                            text: nav.modelData.target ? nav.modelData.target.title : ""
                                            horizontalAlignment: nav.isNext ? Text.AlignRight : Text.AlignLeft
                                            elide: Text.ElideRight
                                            color: "#f0eee7"
                                            font.family: "Inter"
                                            font.pixelSize: 14
                                            font.weight: Font.DemiBold
                                        }
                                    }
                                }
                                background: Item {
                                    Rectangle {
                                        anchors.fill: parent
                                        anchors.margins: 1
                                        radius: 14
                                        color: nav.down ? "#22ffffff" : nav.hovered ? "#12ffffff" : "transparent"
                                        border.color: nav.visualFocus ? "#a6d4bf" : "transparent"
                                        Behavior on color { ColorAnimation { duration: 120 } }
                                    }
                                    Rectangle {
                                        visible: nav.isNext && root.previousPage !== null
                                        height: parent.height
                                        width: 1
                                        color: "#12ffffff"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
