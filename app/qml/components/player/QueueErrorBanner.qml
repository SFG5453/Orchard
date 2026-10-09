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

// Queue load failure with a retry action.
Rectangle {
    Layout.fillWidth: true
    visible: Boolean(OrchardPlayback.queueError)
    implicitHeight: errorColumn.implicitHeight + 20
    radius: 12
    color: "#3a1f1c"
    border.color: "#5e2f2a"

    ColumnLayout {
        id: errorColumn
        anchors.fill: parent
        anchors.margins: 10
        spacing: 8

        RowLayout {
            Layout.fillWidth: true
            spacing: 8

            LucideIcon {
                name: "circle-alert"
                Layout.preferredWidth: 16
                Layout.preferredHeight: 16
                color: "#e6a197"
            }

            Text {
                Layout.fillWidth: true
                text: OrchardPlayback.queueError || ""
                color: "#f5c2bb"
                wrapMode: Text.Wrap
                font.pixelSize: 11
                font.family: "Inter"
            }
        }

        Button {
            id: retryQueue
            text: qsTr("Retry loading queue")
            Layout.fillWidth: true
            implicitHeight: 28
            onClicked: OrchardPlayback.retryQueueLoading()
            background: Rectangle {
                radius: 8
                color: retryQueue.hovered ? "#4d2823" : "#44231f"
            }
            contentItem: Text {
                text: retryQueue.text
                color: "#f5c2bb"
                font.family: "Inter"
                font.pixelSize: 11
                font.bold: true
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
            }
        }
    }
}
