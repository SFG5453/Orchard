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

import Orchard
import QtQuick

KawarpBackground {
    id: root

    // Keep the expensive warp at 60% of logical size, capped at 720p.
    // Only the single-sample composite runs at the window's full resolution.
    readonly property real renderScale: Math.min(0.6, Math.sqrt(1280 * 720 / Math.max(1, width * height)))

    layer.enabled: visible && ready
    layer.textureSize: Qt.size(Math.max(2, Math.round(width * renderScale)), Math.max(2, Math.round(height * renderScale)))
    layer.format: ShaderEffectSource.RGBA16F
    layer.smooth: true

    layer.effect: ShaderEffect {
        property var source

        fragmentShader: "qrc:/shaders/kawarp_composite.frag.qsb"
    }

}
