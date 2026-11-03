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

// Stage background: a blurred copy of the loop under the stage, its edge colours carried toward the lyrics.
Item {
    id: root

    property Item source: null
    property real shape: 1
    property color inkColor: "#0b0d0a"
    // Where the loop sits, in this item's coordinates; the wash lines up with it.
    property rect stageRect: Qt.rect(0, 0, width, height)

    opacity: root.shape
    visible: root.shape > 0.001 && root.source !== null

    // Drawn small from mipmaps so each texel averages its patch; a direct 16x16 render point-samples and shimmers.
    ShaderEffectSource {
        id: detailed
        width: 16
        height: 16
        sourceItem: root.visible ? root.source : null
        textureSize: Qt.size(256, 256)
        mipmap: true
        smooth: true
        live: true
        hideSource: false
    }

    ShaderEffectSource {
        id: averaged
        sourceItem: detailed
        textureSize: Qt.size(16, 16)
        live: true
        hideSource: true
        smooth: true
    }

    ShaderEffect {
        anchors.fill: parent
        property var frame: averaged
        property size texels: Qt.size(16, 16)
        property color ink: root.inkColor
        property rect stage: Qt.rect(root.stageRect.x / Math.max(1, root.width), root.stageRect.y / Math.max(1, root.height),
                                     root.stageRect.width / Math.max(1, root.width), root.stageRect.height / Math.max(1, root.height))
        fragmentShader: "qrc:/shaders/canvas_stage_wash.frag.qsb"
    }
}
