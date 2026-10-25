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

// FSR 1 RCAS: contrast-adaptive sharpening of the EASU output, one texel per pixel.

layout(location = 0) in vec2 qt_TexCoord0;
layout(location = 0) out vec4 fragColor;

layout(std140, binding = 0) uniform buf {
    mat4 qt_Matrix;
    float qt_Opacity;
    vec2 size;
    // Stops below full strength: 0 is the sharpest.
    float stops;
};

layout(binding = 1) uniform sampler2D source;

#define A_GPU 1
#define A_GLSL 1
#include "../../../third_party/FidelityFX-FSR/ffx-fsr/ffx_a.h"

AF4 FsrRcasLoadF(ASU2 p) {
    return texture(source, (vec2(clamp(p, ASU2(0), ASU2(size) - 1)) + 0.5) / size);
}
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}

#define FSR_RCAS_F 1
#include "../../../third_party/FidelityFX-FSR/ffx-fsr/ffx_fsr1.h"

void main() {
    // FsrRcasCon also packs a half-float pair for the FP16 path; the 32-bit path reads only x.
    AU4 con = AU4(AU1_AF1(exp2(-stops)), 0u, 0u, 0u);
    AF1 r, g, b;
    FsrRcasF(r, g, b, AU2(min(qt_TexCoord0 * size, size - 1.0)), con);
    fragColor = vec4(r, g, b, 1.0) * qt_Opacity;
}
