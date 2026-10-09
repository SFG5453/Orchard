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
import Orchard

Item {
    id: root

    property string pageTitle: ""

    Rectangle {
        anchors.fill: parent
        color: "#10120e"
    }

    Column {
        anchors.centerIn: parent
        spacing: 13

        LucideIcon {
            anchors.horizontalCenter: parent.horizontalCenter
            width: 28
            height: 28
            name: "construction"
            color: "#d0aa69"
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: root.pageTitle
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 32
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: qsTr("This section isn’t available yet.")
            color: "#8f9288"
            font.family: "Inter"
            font.pixelSize: 12
        }
    }
}
