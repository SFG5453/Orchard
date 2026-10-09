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

// Translate toggle plus what the translator is up to, pinned over the lyrics.
Row {
    id: bar

    property color accentColor: "#f0eee7"
    property color primaryText: "#f5f3ee"

    readonly property bool on: OrchardTranslation.enabled
    readonly property string status: OrchardTranslation.status
    readonly property bool failed: status === "failed"
    readonly property string label: {
        switch (status) {
        case "downloading":
            return qsTr("Downloading %1 model · %2%").arg(OrchardTranslation.sourceName)
                    .arg(Math.round(OrchardTranslation.progress * 100));
        case "translating":
        case "ready":
        case "unsupported":
        case "failed":
            return OrchardTranslation.message;
        default:
            return "";
        }
    }

    spacing: 6
    layoutDirection: Qt.RightToLeft

    Button {
        id: toggle
        width: 30
        height: 30
        hoverEnabled: true
        Accessible.name: bar.on ? qsTr("Stop translating lyrics") : qsTr("Translate lyrics")
        ToolTip.visible: hovered
        ToolTip.delay: 500
        ToolTip.text: Accessible.name
        onClicked: OrchardTranslation.enabled = !bar.on
        background: Rectangle {
            radius: height / 2
            color: bar.on ? Qt.rgba(bar.accentColor.r, bar.accentColor.g, bar.accentColor.b, toggle.hovered ? 0.3 : 0.22)
                          : toggle.down ? "#26ffffff" : toggle.hovered ? "#1affffff" : "#10ffffff"
            Behavior on color { ColorAnimation { duration: 160 } }
        }
        contentItem: Item {
            LucideIcon {
                anchors.centerIn: parent
                width: 15
                height: 15
                name: "languages"
                color: bar.on ? bar.primaryText : Qt.rgba(bar.primaryText.r, bar.primaryText.g, bar.primaryText.b, 0.6)
            }
        }
    }

    // Failures are clickable; everything else is a quiet caption.
    Button {
        id: caption
        visible: bar.on && bar.label.length > 0
        height: 30
        leftPadding: 10
        rightPadding: 10
        enabled: bar.failed
        hoverEnabled: bar.failed
        Accessible.name: bar.failed ? qsTr("Try translating again") : bar.label
        onClicked: OrchardTranslation.retry()
        background: Rectangle {
            radius: height / 2
            color: caption.hovered ? "#1affffff" : "#10ffffff"
        }
        contentItem: Text {
            text: bar.failed ? qsTr("%1 Try again").arg(bar.label) : bar.label
            color: Qt.rgba(bar.primaryText.r, bar.primaryText.g, bar.primaryText.b, 0.78)
            font.family: "Inter"
            font.pixelSize: 11
            font.bold: true
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
    }
}
