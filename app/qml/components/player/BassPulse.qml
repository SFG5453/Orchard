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

// Kick-drum level from the audio engine's 20 Hz spectrum meter, for visuals that pulse with the music.
QtObject {
    id: root

    property var engine: null
    property bool running: false
    // 0..1, jumps on a bass onset and decays between hits.
    property real level: 0
    // False while the meter is silent (remote playback, gaps), so callers can fall back.
    property bool live: false
    // Slow-moving bass average; onsets are measured against it so loud masters do not pin the pulse.
    property real floor: 0

    function feed(levels) {
        // Bands 1 and 2 are 62 Hz and 125 Hz: kick and bass body. Levels are dB mapped to 0..1.
        const bass = Math.max(+levels[1] || 0, +levels[2] || 0);
        live = bass > 0.05;
        if (!live) {
            level = 0;
            return;
        }
        floor += (bass - floor) * (bass > floor ? 0.06 : 0.12);
        const onset = Math.max(0, Math.min(1, (bass - floor) / 0.1));
        level = Math.max(onset, level * 0.8);
    }

    onRunningChanged: if (!running) { level = 0; live = false; }

    property Connections meters: Connections {
        target: root.engine
        enabled: root.running && root.engine !== null
        function onMetersChanged() { root.feed(root.engine.spectrum); }
    }
}
