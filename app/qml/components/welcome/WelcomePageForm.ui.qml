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

ColumnLayout {
    id: root

    property string eyebrow
    property string title
    property string subtitle
    default property alias content: body.data

    spacing: 0

    Text {
        text: root.eyebrow
        color: "#8a909a"
        font.pixelSize: 12
        font.weight: Font.DemiBold
        font.capitalization: Font.AllUppercase
        font.letterSpacing: 1
        font.family: "Inter"
    }

    Item {
        Layout.preferredHeight: 12
    }

    Text {
        Layout.fillWidth: true
        text: root.title
        color: "#ffffff"
        wrapMode: Text.Wrap
        font.pixelSize: 32
        font.weight: Font.Bold
        font.letterSpacing: -0.5
        font.family: "Inter"
    }

    Item {
        Layout.preferredHeight: 8
    }

    Text {
        Layout.fillWidth: true
        text: root.subtitle
        color: "#8a909a"
        wrapMode: Text.Wrap
        lineHeight: 1.3
        font.pixelSize: 14
        font.family: "Inter"
    }

    Item {
        Layout.preferredHeight: 28
    }

    ColumnLayout {
        id: body

        Layout.fillWidth: true
        spacing: 10
    }
}
