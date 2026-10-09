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

// Caption above one translucent panel. Rows (SettingsRow) set rowSpacing and verticalPadding to 0.
Item {
    id: root
    property string title
    property int rowSpacing: 20
    property int verticalPadding: 22
    default property alias settings: body.data
    property alias slide: slide
    property alias entrance: entrance
    implicitHeight: panel.y + panel.height
    transform: Translate { id: slide; y: 0 }

    // Panels rise in one after another when a section loads. The Mexican wave of settings.
    SequentialAnimation {
        id: entrance
        property int delay: 0
        PauseAnimation { duration: entrance.delay }
        ParallelAnimation {
            NumberAnimation { target: root; property: "opacity"; to: 1; duration: 260; easing.type: Easing.OutCubic }
            NumberAnimation { target: slide; property: "y"; to: 0; duration: 340; easing.type: Easing.OutCubic }
        }
    }

    // Wrapper owns the height; setting height on the Text itself loops with its implicit height.
    Item {
        id: heading
        width: parent.width
        height: caption.visible ? caption.implicitHeight + 10 : 0

        Text {
            id: caption
            x: 4
            width: parent.width - 4
            visible: root.title.length > 0
            text: root.title
            color: "#8d968e"
            font.family: "Inter"
            font.pixelSize: 12
            font.weight: Font.DemiBold
            font.letterSpacing: 0.9
            font.capitalization: Font.AllUppercase
            elide: Text.ElideRight
        }
    }

    Rectangle {
        id: panel
        y: heading.height
        width: parent.width
        height: body.implicitHeight + root.verticalPadding * 2
        radius: 16
        color: "#0affffff"
        border.color: "#1cffffff"

        ColumnLayout {
            id: body
            x: 20
            y: root.verticalPadding
            width: panel.width - 40
            spacing: root.rowSpacing
        }
    }
}
