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

// Transport button: skip, shuffle, repeat, or the large play/pause toggle.
Button {
    id: transport

    property string glyph: "play"
    // Large button whose glyph trades places with altGlyph while altShown.
    property bool main: false
    property string altGlyph: "pause"
    property bool altShown: false
    // Toggle styling for shuffle and repeat.
    property bool mode: false
    property bool modeActive: false
    property real enter: 1
    // Horizontal lurch of the glyph, driven by nudgeAnimation.
    property real nudge: 0
    property string tooltip: ""
    property color accentColor: "#f0eee7"
    property color primaryText: "#f7f5f0"
    property color inkColor: "#0b0d0a"
    property alias nudgeAnimation: nudgeAnim

    implicitWidth: main ? 68 : mode ? 40 : 52
    implicitHeight: implicitWidth
    hoverEnabled: true
    focusPolicy: Qt.TabFocus
    opacity: enter
    scale: 0.6 + 0.4 * enter
    Accessible.name: ToolTip.text
    Accessible.checkable: mode
    Accessible.checked: modeActive
    ToolTip.visible: hovered
    ToolTip.text: tooltip
    ToolTip.delay: 500

    // Skip glyphs lurch the way they send you.
    SequentialAnimation {
        id: nudgeAnim
        NumberAnimation { target: transport; property: "nudge"; to: transport.glyph === "skip-back" ? -7 : 7; duration: 90; easing.type: Easing.OutCubic }
        NumberAnimation { target: transport; property: "nudge"; to: 0; duration: 420; easing.type: Easing.OutBack; easing.overshoot: 2.4 }
    }

    background: Rectangle {
        radius: width / 2
        border.color: transport.activeFocus ? transport.primaryText : "transparent"
        color: transport.main ? (transport.hovered ? Qt.lighter(transport.primaryText, 1.05) : transport.primaryText)
             : transport.modeActive ? Qt.rgba(transport.accentColor.r, transport.accentColor.g, transport.accentColor.b, transport.hovered ? 0.3 : 0.2)
             : transport.hovered && transport.enabled ? "#1cffffff" : "transparent"
        opacity: transport.enabled ? 1 : 0.4
        scale: transport.down ? 0.88 : transport.main && transport.hovered ? 1.05 : 1
        Behavior on color { ColorAnimation { duration: 140 } }
        Behavior on scale { NumberAnimation { duration: 300; easing.type: Easing.OutBack; easing.overshoot: 2.4 } }
    }

    contentItem: Item {
        transform: Translate { x: transport.nudge }
        scale: transport.down ? 0.86 : 1
        Behavior on scale { NumberAnimation { duration: 260; easing.type: Easing.OutBack; easing.overshoot: 2.4 } }

        // Play and pause trade places with a quarter turn instead of a hard cut.
        LucideIcon {
            readonly property bool shown: !(transport.main && transport.altShown)
            anchors.centerIn: parent
            width: transport.main ? 28 : transport.mode ? 18 : 24
            height: width
            name: transport.glyph
            color: transport.main ? Qt.darker(transport.inkColor, 1.2)
                 : !transport.enabled ? "#6b6f68"
                 : transport.modeActive ? transport.accentColor : transport.primaryText
            opacity: shown ? 1 : 0
            scale: shown ? 1 : 0.4
            rotation: shown ? 0 : 90
            Behavior on opacity { NumberAnimation { duration: 200 } }
            Behavior on scale { NumberAnimation { duration: 340; easing.type: Easing.OutBack; easing.overshoot: 2 } }
            Behavior on rotation { NumberAnimation { duration: 340; easing.type: Easing.OutCubic } }
        }

        LucideIcon {
            readonly property bool shown: transport.altShown
            anchors.centerIn: parent
            visible: transport.main
            width: 28
            height: width
            name: transport.altGlyph
            color: Qt.darker(transport.inkColor, 1.2)
            opacity: shown ? 1 : 0
            scale: shown ? 1 : 0.4
            rotation: shown ? 0 : -90
            Behavior on opacity { NumberAnimation { duration: 200 } }
            Behavior on scale { NumberAnimation { duration: 340; easing.type: Easing.OutBack; easing.overshoot: 2 } }
            Behavior on rotation { NumberAnimation { duration: 340; easing.type: Easing.OutCubic } }
        }
    }
}
