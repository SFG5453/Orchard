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
    vec2 texels;
    vec4 ink;
    // The stage's rect in item coordinates (x, y, width, height).
    vec4 stage;
};

layout(binding = 1) uniform sampler2D frame;

vec4 weights(float v) {
    vec4 n = vec4(1.0, 2.0, 3.0, 4.0) - v;
    vec4 s = n * n * n;
    float x = s.x;
    float y = s.y - 4.0 * s.x;
    float z = s.z - 4.0 * s.y + 6.0 * s.x;
    return vec4(x, y, z, 6.0 - x - y - z) / 6.0;
}

// Cubic B-spline from four bilinear taps; on a tiny texture it reads as a heavy blur.
vec4 bspline(vec2 uv) {
    uv = uv * texels - 0.5;
    vec2 f = fract(uv);
    uv -= f;
    vec4 xw = weights(f.x);
    vec4 yw = weights(f.y);
    vec4 c = uv.xxyy + vec2(-0.5, 1.5).xyxy;
    vec4 s = vec4(xw.xz + xw.yw, yw.xz + yw.yw);
    vec4 o = (c + vec4(xw.yw, yw.yw) / s) / texels.xxyy;
    vec4 a = mix(texture(frame, o.yw), texture(frame, o.xw), s.x / (s.x + s.y));
    vec4 b = mix(texture(frame, o.yz), texture(frame, o.xz), s.x / (s.x + s.y));
    return mix(a, b, s.z / (s.z + s.w));
}

void main() {
    vec2 p = (qt_TexCoord0 - stage.xy) / stage.zw;
    // Past the stage the edge colours carry on, spreading vertically so details smear into a gradient.
    float beyond = max(0.0, p.x - 1.0) + max(0.0, -p.x);
    float spread = min(0.45, beyond * 0.5);
    p = clamp(p, 0.0, 1.0);
    vec3 c = bspline(p).rgb * 0.4;
    c += bspline(vec2(p.x, p.y - spread * 0.5)).rgb * 0.2;
    c += bspline(vec2(p.x, p.y + spread * 0.5)).rgb * 0.2;
    c += bspline(vec2(p.x, p.y - spread)).rgb * 0.1;
    c += bspline(vec2(p.x, p.y + spread)).rgb * 0.1;
    float grey = dot(c, vec3(0.299, 0.587, 0.114));
    c = clamp(mix(vec3(grey), c, 1.25), 0.0, 1.0);
    // Matches the stage shade at its edge, then darkens toward the lyrics so text stays readable.
    c = mix(c, ink.rgb, mix(0.2, 0.55, smoothstep(0.0, 0.6, beyond)));
    fragColor = vec4(c, 1.0) * qt_Opacity;
}
