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

// Standard or adaptive crossfade; lives inside the Transitions group.
SettingsRow {
    id: root

    property bool crossfadeEnabled: true
    property bool adaptiveAvailable: true
    property bool audioEngineEnabled: false
    // "standard" or "adaptive".
    property string adaptiveMode: "standard"
    property string adaptiveStatus: ""
    property alias picker: picker

    iconName: "shuffle"
    title: qsTr("Crossfade style")
    enabled: crossfadeEnabled
    // MAX plays Qobuz audio, which skips the mix worker, so it benches adaptive mix too.
    description: (!adaptiveAvailable
        ? qsTr("Use the same crossfade length between songs. Adaptive mix is unavailable while streaming quality is MAX.")
        : audioEngineEnabled && adaptiveMode !== "adaptive"
        ? qsTr("Use the same crossfade length between songs. Adaptive mix is unavailable while the audio engine is on.")
        : adaptiveMode === "adaptive"
        ? qsTr("Match beats and phrases, balance the blend, and shape the handoff to each pair of songs. Analysis stays on this device.")
        : qsTr("Use the same crossfade length between songs."))
        + (adaptiveMode === "adaptive" && adaptiveStatus.length > 0 ? " " + adaptiveStatus : "")

    SettingsSegmented {
        id: picker
        currentValue: root.adaptiveMode
        model: [
            { value: "standard", label: qsTr("Standard") },
            {
                value: "adaptive", label: qsTr("Adaptive mix"),
                // One DSP chef at a time; the audio engine and adaptive mix share no kitchen.
                enabled: root.adaptiveAvailable && (!root.audioEngineEnabled || root.adaptiveMode === "adaptive")
            }
        ]
    }
}
