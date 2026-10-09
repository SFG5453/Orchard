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
import QtQuick.Controls

// I'm sorry Dave, this song was written by me.
Rectangle {
    id: root

    property string trackId: ""
    // Reading revision re-runs the lookup whenever a verdict lands.
    readonly property bool flagged: trackId !== "" && OrchardPlayback.slop.revision >= 0
                                    && OrchardPlayback.slop.isFlagged(trackId)

    visible: flagged
    width: label.implicitWidth + 8
    height: 14
    radius: 3
    color: "#40d9a441"
    Accessible.role: Accessible.StaticText
    Accessible.name: qsTr("Likely AI-generated")

    Text {
        id: label
        anchors.centerIn: parent
        text: qsTr("AI")
        color: "#f2d39a"
        font.family: "Inter"
        font.pixelSize: 9
        font.weight: Font.DemiBold
    }

    HoverHandler { id: hover }
    ToolTip.visible: hover.hovered
    ToolTip.delay: 400
    ToolTip.text: qsTr("Orchard detected AI-generation artifacts in this track (%1% confidence).")
                  .arg(Math.round(OrchardPlayback.slop.probability(trackId) * 100))
}
