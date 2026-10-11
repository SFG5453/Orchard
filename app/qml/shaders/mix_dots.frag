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
    vec2 size;
    float fill;
    float band;
    float pitch;
    float time;
    vec4 baseColor;
    vec4 dotColor;
};

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec2 px = qt_TexCoord0 * size;
    float r = size.y * 0.5;
    float end = max(fill * size.x, size.y);

    // Capsule covering the elapsed part of the track.
    vec2 c = vec2(clamp(px.x, r, end - r), r);
    float inside = 1.0 - smoothstep(-0.5, 0.5, length(px - c) - r);

    // 0 where the band starts, 1 at the playhead.
    float w = band > 0.5 ? clamp((px.x - (end - band)) / band, 0.0, 1.0) : 0.0;
    w = w * w * (3.0 - 2.0 * w);

    vec2 cell = floor(px / pitch);
    vec2 local = (fract(px / pitch) - 0.5) * pitch;
    float h = hash(cell);
    float h2 = hash(cell + 17.0);
    float dotMask = 1.0 - smoothstep(pitch * 0.26, pitch * 0.26 + 0.7, length(local));
    // Sparse at the band's tail, packed near the playhead.
    float present = step(h2, w * 1.15);
    float twinkle = 0.3 + 0.7 * (0.5 + 0.5 * sin(time * (2.0 + 3.0 * h) + h * 6.2832));
    float wave = 0.75 + 0.25 * sin(px.x * 0.09 - time * 5.0);
    float dotA = dotMask * present * twinkle * wave * (0.4 + 0.6 * w) * dotColor.a;

    // Solid fill gives way to an accent wash under the dots.
    vec3 underRgb = mix(baseColor.rgb, dotColor.rgb, w);
    float underA = mix(baseColor.a, 0.3, w);
    vec3 rgb = dotColor.rgb * dotA + underRgb * underA * (1.0 - dotA);
    float a = dotA + underA * (1.0 - dotA);
    fragColor = vec4(rgb, a) * inside * qt_Opacity;
}
