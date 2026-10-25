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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#version 440
#extension GL_GOOGLE_include_directive : require

// FSR 1 EASU: edge-adaptive upscale of the video frame to the picture's pixel size.

layout(location = 0) in vec2 qt_TexCoord0;
layout(location = 0) out vec4 fragColor;

layout(std140, binding = 0) uniform buf {
    mat4 qt_Matrix;
    float qt_Opacity;
    vec2 inputSize;
    vec2 outputSize;
};

layout(binding = 1) uniform sampler2D source;

#define A_GPU 1
#define A_GLSL 1
#include "../../../third_party/FidelityFX-FSR/ffx-fsr/ffx_a.h"

// One channel of the 2x2 texels around p, in textureGather order. Linear sampling at
// texel centers keeps Qt's texture orientation on every graphics API.
vec4 gatherChannel(vec2 p, int channel) {
    vec2 texel = 1.0 / inputSize;
    vec2 base = (floor(p * inputSize - 0.5) + 0.5) * texel;
    return vec4(texture(source, base + vec2(0.0, texel.y))[channel],
                texture(source, base + texel)[channel],
                texture(source, base + vec2(texel.x, 0.0))[channel],
                texture(source, base)[channel]);
}

AF4 FsrEasuRF(AF2 p) { return gatherChannel(p, 0); }
AF4 FsrEasuGF(AF2 p) { return gatherChannel(p, 1); }
AF4 FsrEasuBF(AF2 p) { return gatherChannel(p, 2); }

#define FSR_EASU_F 1
#include "../../../third_party/FidelityFX-FSR/ffx-fsr/ffx_fsr1.h"

void main() {
    AU4 con0, con1, con2, con3;
    FsrEasuCon(con0, con1, con2, con3, inputSize.x, inputSize.y, inputSize.x, inputSize.y, outputSize.x, outputSize.y);
    AF3 color;
    FsrEasuF(color, AU2(min(qt_TexCoord0 * outputSize, outputSize - 1.0)), con0, con1, con2, con3);
    fragColor = vec4(color, 1.0) * qt_Opacity;
}
