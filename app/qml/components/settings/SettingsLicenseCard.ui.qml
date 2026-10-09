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

// Row for one bundled open source project.
SettingsRow {
    id: card

    property string name: "Lucide Icons"
    property string license: "ISC / MIT"
    property string blurb: "Clean, consistent iconography."
    property alias linkButton: linkButton
    property alias licenseButton: licenseButton

    title: card.name
    description: card.blurb

    Rectangle {
        radius: 6
        color: "#12ffffff"
        border.color: "#1cffffff"
        implicitHeight: 22
        implicitWidth: licenseBadge.implicitWidth + 14

        Text {
            id: licenseBadge
            anchors.centerIn: parent
            text: card.license
            color: "#b8bab3"
            font.family: "Inter"
            font.pixelSize: 11
            font.weight: Font.Medium
        }
    }

    SettingsButton {
        id: linkButton
        text: qsTr("Website")
    }

    SettingsButton {
        id: licenseButton
        text: qsTr("License")
    }
}
