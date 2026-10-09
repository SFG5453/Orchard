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
import "../home"

// The covers trade places on the audio sample clock, including while paused.
Item {
    id: root
    property url source: ""
    property url incomingSource: ""
    property real radius: 10
    property bool transitioning: false
    property real progress: 0
    property bool motionEnabled: true
    // Cloudy dissolve for the fullscreen cover. Sliding a 500px cover sideways looks like a bug.
    property bool dissolve: false
    // Fresh blob layout per mix so no two handoffs look rehearsed.
    property real seed: 0
    onTransitioningChanged: if (transitioning) seed = Math.random() * 64
    readonly property real p: Math.max(0, Math.min(1, progress))
    readonly property real handoff: Math.max(0, Math.min(1, (p - 0.28) / 0.5))
    readonly property real eased: handoff * handoff * (3 - 2 * handoff)

    RoundedArtwork {
        id: outgoingArt
        anchors.fill: parent
        source: root.source
        radius: root.radius
        opacity: root.transitioning && !root.dissolve ? 1 - root.eased : 1
        scale: root.transitioning && root.motionEnabled && !root.dissolve ? 1 - 0.16 * root.eased : 1
        transform: Translate { x: root.transitioning && root.motionEnabled && !root.dissolve ? -root.width * 0.12 * root.eased : 0 }
    }
    RoundedArtwork {
        id: incomingArt
        anchors.fill: parent
        source: root.incomingSource
        radius: root.radius
        visible: root.transitioning && (root.dissolve || Boolean(source.toString()))
        opacity: root.dissolve ? 1 : root.eased
        scale: root.motionEnabled && !root.dissolve ? 0.86 + 0.14 * root.eased : 1
        transform: Translate { x: root.motionEnabled && !root.dissolve ? root.width * 0.22 * (1 - root.eased) : 0 }
    }
    // Both covers render offscreen and the shader bleeds the new one through in blobs.
    Loader {
        anchors.fill: parent
        active: root.dissolve && root.transitioning
        sourceComponent: ShaderEffect {
            property var outgoing: ShaderEffectSource { sourceItem: outgoingArt; hideSource: true }
            property var incoming: ShaderEffectSource { sourceItem: incomingArt; hideSource: true }
            property real progress: root.eased
            property real seed: root.seed
            fragmentShader: "qrc:/shaders/artwork_dissolve.frag.qsb"
        }
    }
    // A quiet shared edge echoes the reference's settling cover, without
    // turning the album into a PowerPoint slide that discovered caffeine.
    Rectangle {
        anchors.fill: parent
        radius: root.radius
        color: "transparent"
        border.color: "#d7e4dc"
        border.width: 1
        opacity: root.transitioning ? 0.22 * Math.sin(Math.PI * root.p) : 0
    }
}
