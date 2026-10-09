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

// Best Mix action with analysis progress.
Button {
    id: bestMix
    required property var panel
    property int count: 0

    function tint(color, alpha) {
        return Qt.rgba(color.r, color.g, color.b, alpha);
    }

    objectName: "bestMixButton"
    visible: OrchardPlayback.crossfadeEnabled && OrchardPlayback.adaptiveMix.mode === "adaptive"
    readonly property bool busy: OrchardPlayback.bestMix.busy
    readonly property bool sorting: OrchardPlayback.bestMix.sorting
    readonly property bool downloading: OrchardPlayback.bestMix.downloading
    readonly property int done: downloading ? OrchardPlayback.bestMix.downloaded
                                            : OrchardPlayback.bestMix.completed
    readonly property real fraction: OrchardPlayback.bestMix.total > 0
                                     ? done / OrchardPlayback.bestMix.total : 0
    text: sorting ? qsTr("Finding transitions…")
          : downloading ? qsTr("Downloading %1/%2").arg(done).arg(OrchardPlayback.bestMix.total)
          : busy ? qsTr("Analyzing %1/%2").arg(done).arg(OrchardPlayback.bestMix.total)
          : OrchardPlayback.bestMixSorted ? qsTr("Restore order") : qsTr("Best Mix")
    enabled: busy || OrchardPlayback.bestMixSorted || count > 1
    implicitHeight: 28
    leftPadding: 10
    rightPadding: 12
    Accessible.name: busy ? qsTr("Cancel Best Mix") : text
    ToolTip.visible: hovered && busy
    ToolTip.text: qsTr("Click to cancel")
    ToolTip.delay: 600
    onClicked: OrchardPlayback.toggleBestMix()

    background: Rectangle {
        radius: height / 2
        clip: true
        color: bestMix.tint(panel.accentColor, !bestMix.enabled ? 0.06 : bestMix.down ? 0.3 : bestMix.hovered ? 0.24 : 0.16)
        border.color: bestMix.activeFocus ? panel.accentColor : bestMix.tint(panel.accentColor, 0.28)
        Behavior on color { ColorAnimation { duration: 120 } }

        // Fills left to right while tracks are analyzed.
        Rectangle {
            anchors.left: parent.left
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            radius: parent.radius
            width: bestMix.busy ? parent.width * (bestMix.sorting ? 1 : bestMix.fraction) : 0
            color: bestMix.tint(panel.accentColor, 0.22)
            Behavior on width { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
        }
    }

    contentItem: RowLayout {
        spacing: 6
        opacity: bestMix.enabled ? 1 : 0.45

        LucideIcon {
            id: mixIcon
            Layout.preferredWidth: 13
            Layout.preferredHeight: 13
            name: OrchardPlayback.bestMixSorted && !bestMix.busy ? "undo-2" : "sparkles"
            color: panel.accentColor
            NumberAnimation on rotation {
                from: 0; to: 360; duration: 1400
                loops: Animation.Infinite
                running: bestMix.busy && panel.visible
                onRunningChanged: if (!running) mixIcon.rotation = 0
            }
        }

        Text {
            Layout.fillWidth: true
            text: bestMix.text
            elide: Text.ElideRight
            color: panel.primaryText
            font.family: "Inter"
            font.pixelSize: 11
            font.bold: true
        }
    }
}
