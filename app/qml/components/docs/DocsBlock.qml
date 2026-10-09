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
import QtQuick.Layouts

// One paragraph, list, subheading, or code block of a page.
ColumnLayout {
    id: root
    // {kind, text} or {kind, items}; see DocsLibrary::sections().
    required property var block

    readonly property string kind: root.block.kind

    spacing: 10

    Text {
        Layout.fillWidth: true
        visible: root.kind === "paragraph"
        text: visible ? root.block.text : ""
        textFormat: Text.MarkdownText
        wrapMode: Text.WordWrap
        lineHeight: 1.4
        color: "#cfd2ca"
        font.family: "Inter"
        font.pixelSize: 14
    }

    Text {
        Layout.fillWidth: true
        Layout.topMargin: 6
        visible: root.kind === "subheading"
        text: visible ? root.block.text : ""
        wrapMode: Text.WordWrap
        color: "#f0eee7"
        font.family: "Inter"
        font.pixelSize: 15
        font.weight: Font.Medium
    }

    // Numbered steps lead with an accent numeral so a procedure reads at a glance.
    ColumnLayout {
        Layout.fillWidth: true
        visible: root.kind === "steps"
        spacing: 10

        Repeater {
            model: root.kind === "steps" ? root.block.items : []

            delegate: RowLayout {
                id: step
                required property string modelData
                required property int index

                Layout.fillWidth: true
                spacing: 14

                Text {
                    Layout.alignment: Qt.AlignTop
                    Layout.preferredWidth: 18
                    Layout.topMargin: 1
                    text: step.index + 1
                    horizontalAlignment: Text.AlignHCenter
                    color: "#8cc5a5"
                    font.family: "Inter"
                    font.pixelSize: 14
                    font.weight: Font.DemiBold
                }
                Text {
                    Layout.fillWidth: true
                    Layout.topMargin: 1
                    text: step.modelData
                    textFormat: Text.MarkdownText
                    wrapMode: Text.WordWrap
                    lineHeight: 1.35
                    color: "#cfd2ca"
                    font.family: "Inter"
                    font.pixelSize: 14
                }
            }
        }
    }

    ColumnLayout {
        Layout.fillWidth: true
        visible: root.kind === "bullets"
        spacing: 8

        Repeater {
            model: root.kind === "bullets" ? root.block.items : []

            delegate: RowLayout {
                id: bullet
                required property string modelData

                Layout.fillWidth: true
                spacing: 14

                Rectangle {
                    Layout.alignment: Qt.AlignTop
                    Layout.topMargin: 8
                    Layout.leftMargin: 8
                    Layout.preferredWidth: 6
                    Layout.preferredHeight: 6
                    radius: 3
                    color: "#a8cdb6"
                }
                Text {
                    Layout.fillWidth: true
                    text: bullet.modelData
                    textFormat: Text.MarkdownText
                    wrapMode: Text.WordWrap
                    lineHeight: 1.35
                    color: "#cfd2ca"
                    font.family: "Inter"
                    font.pixelSize: 14
                }
            }
        }
    }

    Rectangle {
        Layout.fillWidth: true
        visible: root.kind === "code"
        implicitHeight: codeText.implicitHeight + 24
        radius: 10
        color: "#181d1a"
        border.color: "#354039"

        Text {
            id: codeText
            anchors.fill: parent
            anchors.margins: 12
            text: root.kind === "code" ? root.block.text : ""
            wrapMode: Text.WrapAnywhere
            color: "#c4e0cb"
            font.family: "monospace"
            font.pixelSize: 13
        }
    }
}
