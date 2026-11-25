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
import "../settings"
import ".."

// Announcement above a release's sections, with one link. Hidden when the release has none.
Rectangle {
    id: root

    property string title
    property string body
    property string action
    property string url

    visible: root.title !== ""
    implicitHeight: content.implicitHeight + 36
    radius: 16
    color: Qt.rgba(OrchardAppearance.accent.r, OrchardAppearance.accent.g, OrchardAppearance.accent.b, 0.12)
    border.color: Qt.rgba(OrchardAppearance.accent.r, OrchardAppearance.accent.g, OrchardAppearance.accent.b, 0.34)

    RowLayout {
        id: content
        x: 20
        y: 18
        width: root.width - 40
        spacing: 18

        LucideIcon {
            Layout.alignment: Qt.AlignTop
            Layout.preferredWidth: 20
            Layout.preferredHeight: 20
            name: "message-circle"
            color: OrchardAppearance.accentStrong
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            Text {
                Layout.fillWidth: true
                text: root.title
                wrapMode: Text.WordWrap
                color: "#f0eee7"
                font.family: "Inter"
                font.pixelSize: 16
                font.weight: Font.DemiBold
            }
            Text {
                Layout.fillWidth: true
                text: root.body
                wrapMode: Text.WordWrap
                color: "#d6d9d3"
                font.family: "Inter"
                font.pixelSize: 14
                lineHeight: 1.25
            }
        }
        SettingsButton {
            Layout.alignment: Qt.AlignVCenter
            visible: root.url !== ""
            highlighted: true
            text: root.action
            onClicked: Qt.openUrlExternally(root.url)
        }
    }
}
