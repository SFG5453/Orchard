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

// One-line label that lifts out as the next song's label rises in, on the cover handoff curve.
Item {
    id: root
    property string text: ""
    property string incomingText: ""
    property bool transitioning: false
    property real progress: 0
    property real maxWidth: 1000
    property color color: "white"
    property font font
    readonly property real handoff: Math.max(0, Math.min(1, (progress - 0.28) / 0.5))
    readonly property real eased: handoff * handoff * (3 - 2 * handoff)
    // Rest position of the incoming label equals the normal one, so the mix end has no jump.
    readonly property real lift: Math.round(height * 0.7)
    readonly property bool swapping: transitioning && incomingText.length > 0
    readonly property real shownWidth: swapping ? outgoing.implicitWidth + (incoming.implicitWidth - outgoing.implicitWidth) * eased
                                                : outgoing.implicitWidth

    width: Math.min(shownWidth, maxWidth)
    implicitHeight: outgoing.implicitHeight
    clip: swapping

    Text {
        id: outgoing
        width: root.width
        text: root.text
        color: root.color
        font: root.font
        elide: Text.ElideRight
        maximumLineCount: 1
        opacity: root.swapping ? 1 - root.eased : 1
        y: root.swapping ? -root.lift * root.eased : 0
    }
    Text {
        id: incoming
        width: root.width
        visible: root.swapping
        text: root.incomingText
        color: root.color
        font: root.font
        elide: Text.ElideRight
        maximumLineCount: 1
        opacity: root.eased
        y: root.lift * (1 - root.eased)
    }
}
