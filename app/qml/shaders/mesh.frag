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

layout(location = 0) in vec2 qt_TexCoord0;
layout(location = 0) out vec4 fragColor;

layout(std140, binding = 0) uniform buf {
    mat4 qt_Matrix;
    float qt_Opacity;
    vec2 extent;
    float time;
};

void main() {
    vec2 uv = qt_TexCoord0;

    // Very slow ambient movement.
    float t = time * 0.10;

    // Near-neutral dark base.
    vec3 color = vec3(0.035, 0.040, 0.050);

    // Broad, low-intensity purple tint.
    vec2 pos1 = vec2(
        0.30 + 0.18 * sin(t * 0.70),
        0.38 + 0.14 * cos(t * 0.55)
    );

    float glow1 = 1.0 - smoothstep(
        0.0,
        0.90,
        distance(uv, pos1)
    );

    color += vec3(0.065, 0.020, 0.080) * glow1;

    // Soft blue tint on the opposite side.
    vec2 pos2 = vec2(
        0.72 + 0.16 * cos(t * 0.45 + 1.2),
        0.48 + 0.12 * sin(t * 0.60 + 0.5)
    );

    float glow2 = 1.0 - smoothstep(
        0.0,
        0.95,
        distance(uv, pos2)
    );

    color += vec3(0.015, 0.045, 0.075) * glow2;

    // Very faint green undertone near the lower portion.
    vec2 pos3 = vec2(
        0.52 + 0.12 * sin(t * 0.50 + 2.0),
        0.76 + 0.10 * cos(t * 0.40 + 1.5)
    );

    float glow3 = 1.0 - smoothstep(
        0.0,
        1.00,
        distance(uv, pos3)
    );

    color += vec3(0.010, 0.040, 0.030) * glow3;

    // Extremely light dithering to reduce visible banding.
    float noise =
        fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453)
        - 0.5;

    color += vec3(noise * 0.003);

    fragColor = vec4(color, 1.0) * qt_Opacity;
}