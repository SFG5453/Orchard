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

// Three dots for an instrumental gap or a loading state. A progress in 0..1 fills them
// left to right over the gap; a negative progress breathes them as a wave.
Row {
    id: dots

    property bool active: false
    property bool running: true
    property color color: "#f0eee7"
    property real progress: -1
    // Phase of the breathing cycle, 0..1.
    property real phase: 0

    spacing: 8
    height: active ? 40 : 0
    opacity: active ? 1 : 0
    clip: true
    Behavior on height { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
    Behavior on opacity { NumberAnimation { duration: 240 } }

    NumberAnimation on phase {
        running: dots.active && dots.running
        from: 0
        to: 1
        duration: 2600
        loops: Animation.Infinite
    }

    // Every line carries one of these; the dots exist only while one is open.
    Repeater {
        model: dots.active || dots.height > 0 ? 3 : 0
        Rectangle {
            id: dot
            required property int index
            readonly property real wave: (1 + Math.sin(2 * Math.PI * (dots.phase - index * 0.12))) / 2
            readonly property real lit: dots.progress < 0 ? 1 : Math.max(0, Math.min(1, dots.progress * 3 - index))
            // Pops in one after another when the gap opens.
            property real shown: dots.active ? 1 : 0
            Behavior on shown {
                SequentialAnimation {
                    PauseAnimation { duration: dots.active ? dot.index * 80 : 0 }
                    NumberAnimation { duration: 380; easing.type: Easing.OutBack; easing.overshoot: 2 }
                }
            }

            y: 15
            width: 9
            height: 9
            radius: 4.5
            color: dots.color
            scale: shown * (0.78 + 0.22 * lit + 0.14 * wave * lit)
            opacity: 0.25 + 0.75 * lit * (dots.progress < 0 ? 0.45 + 0.55 * wave : 0.8 + 0.2 * wave)
        }
    }
}
