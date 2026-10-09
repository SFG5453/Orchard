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

// One device on the Orchard account.
RowLayout {
    id: root

    property string name: "Studio desktop"
    property string detail: "Linux · This device"
    property bool removable: false
    property alias removeButton: removeButton

    Layout.fillWidth: true
    spacing: 12

    ColumnLayout {
        Layout.fillWidth: true
        spacing: 2

        Text {
            Layout.fillWidth: true
            text: root.name
            elide: Text.ElideRight
            color: "#f0eee7"
            font.family: "Inter"
            font.pixelSize: 13
        }

        Text {
            Layout.fillWidth: true
            text: root.detail
            elide: Text.ElideRight
            color: "#a4aaa1"
            font.family: "Inter"
            font.pixelSize: 11
        }
    }

    SettingsButton {
        id: removeButton
        visible: root.removable
        text: qsTr("Remove")
    }
}
