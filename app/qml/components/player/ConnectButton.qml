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
import QtQuick
import QtQuick.Controls

// Player bar cast button and its Orchard Connect device picker.
// OrchardConnect is a context property from main.cpp.
// qmllint disable unqualified
Button {
    id: root

    required property Item backdrop
    property color accentColor: "white"
    property color controlColor: "white"
    property color focusColor: "white"
    // Lit while this desktop drives another device, or one drives it.
    readonly property bool linked: OrchardConnect.controlling || OrchardConnect.controllers.length > 0

    implicitWidth: 32
    implicitHeight: 32
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    Accessible.name: qsTr("Orchard Connect")
    ToolTip.visible: hovered
    ToolTip.delay: 500
    ToolTip.text: OrchardConnect.controlling ? qsTr("Playing on %1").arg(OrchardConnect.peer.name)
                                             : qsTr("Orchard Connect")
    onClicked: picker.open()

    background: Rectangle {
        radius: width / 2
        color: root.linked ? Qt.rgba(root.accentColor.r, root.accentColor.g, root.accentColor.b, root.hovered ? 0.3 : 0.2)
                           : root.hovered ? "#1fffffff" : "transparent"
        border.color: root.activeFocus ? root.focusColor : "transparent"

        Behavior on color { ColorAnimation { duration: 90 } }
    }

    contentItem: LucideIcon {
        name: "cast"
        color: root.linked ? root.accentColor : root.controlColor
        implicitWidth: 17
        implicitHeight: 17
    }

    // Parented to the window overlay, so it opens in the same place wherever the button sits.
    ConnectPopup {
        id: picker
        backdrop: root.backdrop
    }
}
