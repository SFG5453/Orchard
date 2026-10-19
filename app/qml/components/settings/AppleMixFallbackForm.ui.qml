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

// What adaptive mix does for Apple Music songs with no YouTube Music upload to plan from.
SettingsRow {
    id: root

    property bool scanAvailable: true
    // "standard" or "scan".
    property string fallback: "standard"
    property alias picker: picker

    iconName: "audio-lines"
    title: qsTr("Apple Music songs without a match")
    description: !scanAvailable
        ? qsTr("Adaptive mix plans Apple Music songs from their YouTube Music uploads. Songs without one crossfade with Standard. Scanning is not included in this build.")
        : fallback === "scan"
        ? qsTr("Adaptive mix plans Apple Music songs from their YouTube Music uploads. Songs without one are scanned in Apple's player at double speed, which needs a few minutes of the current song, uses up to about 500 MB more memory while it runs and counts as extra plays on your Apple Music account.")
        : qsTr("Adaptive mix plans Apple Music songs from their YouTube Music uploads. Songs without one crossfade with Standard.")

    SettingsSegmented {
        id: picker
        currentValue: root.fallback
        model: [
            { value: "standard", label: qsTr("Standard") },
            { value: "scan", label: qsTr("Scan"), enabled: root.scanAvailable }
        ]
    }
}
