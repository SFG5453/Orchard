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

// Title bar and scrolling text of the license dialog.
ColumnLayout {
    id: root

    property string licenseTitle: "License"
    property string licenseText: ""
    property bool copied: false
    property alias copyButton: copyButton
    property alias closeButton: closeButton
    property alias textArea: licenseViewerText

    spacing: 14

    RowLayout {
        Layout.fillWidth: true
        spacing: 12

        Text {
            Layout.fillWidth: true
            text: root.licenseTitle
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 17
            font.weight: Font.DemiBold
            elide: Text.ElideRight
        }

        SettingsButton {
            id: copyButton
            text: root.copied ? qsTr("Copied!") : qsTr("Copy")
        }

        SettingsButton {
            id: closeButton
            text: "×"
            font.pixelSize: 20
            Accessible.name: qsTr("Close license dialog")
        }
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.fillHeight: true
        color: "#0f1215"
        radius: 8
        border.color: "#20ffffff"
        border.width: 1
        clip: true

        ScrollView {
            anchors.fill: parent
            anchors.margins: 14
            contentWidth: availableWidth

            TextArea {
                id: licenseViewerText
                width: parent.width
                readOnly: true
                selectByMouse: true
                text: root.licenseText
                color: "#cfd3cc"
                font.family: "monospace"
                font.pixelSize: 11
                wrapMode: TextEdit.Wrap
                background: null
            }
        }
    }
}
