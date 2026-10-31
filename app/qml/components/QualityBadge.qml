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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

import QtQuick
import QtQuick.Controls

// Qobuz quality tag: "Hi-Res" or "Lossless". Bragging rights, measured in bits.
Rectangle {
    id: root

    // {tier: "hires"|"lossless", bitDepth, sampleRate}; empty hides the badge.
    property var quality: ({})
    property color textColor: "#f2f0eb"

    // Untyped helpers: compiled bindings that read a C++ map crash qmlcachegen.
    function tierLabel(value) {
        if (!value || !value.tier)
            return "";
        return value.tier === "hires" ? qsTr("Hi-Res") : qsTr("Lossless");
    }
    function detail(value) {
        if (!value || !value.tier)
            return "";
        const parts = [];
        if (value.bitDepth)
            parts.push(qsTr("%1-bit").arg(value.bitDepth));
        if (value.sampleRate)
            parts.push(qsTr("%1 kHz").arg(Number((value.sampleRate / 1000).toFixed(1))));
        return parts.length ? qsTr("Qobuz %1 · %2").arg(tierLabel(value)).arg(parts.join(" / "))
                            : qsTr("Qobuz %1").arg(tierLabel(value));
    }

    readonly property string label: tierLabel(quality)

    visible: label.length > 0
    implicitWidth: text.implicitWidth + 10
    implicitHeight: 16
    radius: 4
    color: "#30ffffff"
    Accessible.role: Accessible.StaticText
    Accessible.name: detail(quality)

    Text {
        id: text
        anchors.centerIn: parent
        text: root.label
        color: root.textColor
        font.family: "Inter"
        font.pixelSize: 9
        font.weight: Font.Bold
        font.letterSpacing: 0.6
    }

    HoverHandler { id: hover }
    ToolTip.visible: hover.hovered
    ToolTip.delay: 300
    ToolTip.text: detail(quality)
}
