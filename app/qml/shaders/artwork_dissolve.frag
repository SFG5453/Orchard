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
    float progress;
    float seed;
};

layout(binding = 1) uniform sampler2D outgoing;
layout(binding = 2) uniform sampler2D incoming;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Blurs a cover so the far side of a blob reads as out of focus.
vec4 soft(sampler2D tex, vec2 uv, float r) {
    vec4 c = texture(tex, uv) * 0.2;
    c += texture(tex, uv + vec2(r, 0.0)) * 0.1;
    c += texture(tex, uv - vec2(r, 0.0)) * 0.1;
    c += texture(tex, uv + vec2(0.0, r)) * 0.1;
    c += texture(tex, uv - vec2(0.0, r)) * 0.1;
    c += texture(tex, uv + vec2(r, r) * 0.7) * 0.1;
    c += texture(tex, uv - vec2(r, r) * 0.7) * 0.1;
    c += texture(tex, uv + vec2(r, -r) * 0.7) * 0.1;
    c += texture(tex, uv - vec2(r, -r) * 0.7) * 0.1;
    return c;
}

void main() {
    vec2 uv = qt_TexCoord0;
    vec2 p = uv + seed;
    float n = 0.6 * noise(p * 5.0) + 0.3 * noise(p * 11.0) + 0.1 * noise(p * 23.0);

    // Remap so progress 0 and 1 are fully one cover despite the soft edge.
    const float edge = 0.16;
    float t = progress * (1.0 + 2.0 * edge) - edge;
    float m = smoothstep(n - edge, n + edge, t);
    float seam = 4.0 * m * (1.0 - m);

    vec4 a = soft(outgoing, uv, 0.018 * seam + 0.01 * progress);
    vec4 b = soft(incoming, uv, 0.022 * (1.0 - m));
    vec4 c = mix(a, b, m);
    // Darken the blob rims so the new cover looks like it is surfacing.
    c.rgb *= 1.0 - 0.28 * seam;
    fragColor = c * qt_Opacity;
}
