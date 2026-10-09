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
import "../home"
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Placeholder shown while the queue has no entries.
Item {
    required property var panel

    anchors.centerIn: parent
    width: parent.width - 28
    height: 160
    
    ColumnLayout {
        anchors.centerIn: parent
        spacing: 10
        width: parent.width

        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: 56
            Layout.preferredHeight: 56
            radius: 28
            color: panel.tint(panel.accentColor, 0.12)
            border.color: panel.tint(panel.accentColor, 0.25)

            LucideIcon {
                anchors.centerIn: parent
                name: "list-music"
                width: 24
                height: 24
                color: panel.accentColor
            }
        }

        Text {
            Layout.fillWidth: true
            text: qsTr("Your queue is empty")
            color: panel.primaryText
            font.family: "Inter"
            font.pixelSize: 13
            font.bold: true
            horizontalAlignment: Text.AlignHCenter
        }

        Text {
            Layout.fillWidth: true
            text: OrchardPlayback.autoplayEnabled
                  ? qsTr("Autoplay will keep things going.\nOr add songs from their menu.")
                  : qsTr("Play a playlist or add songs\nfrom their menu to start listening.")
            color: panel.mutedText
            font.family: "Inter"
            font.pixelSize: 11
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            lineHeight: 1.2
        }
    }
}
