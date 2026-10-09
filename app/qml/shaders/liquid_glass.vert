// Adapted from LiquidGlass. Copyright (c) 2026 ybouane.
// SPDX-License-Identifier: MIT
// See third_party/liquidglass/LICENSE (also bundled at :/licenses/liquidglass/LICENSE).
#version 440

layout(location = 0) in vec2 position;
layout(location = 0) out vec2 vLocal;
layout(location = 1) out vec2 vUV;
layout(std140, binding = 0) uniform buf {
    mat4 mvp;
    vec4 quad;    // padded quad in item units: x, y, w, h
    vec4 panel;   // device px: width, height, corner radius, bevel depth
    vec4 optics;  // refraction, chromatic aberration, edge highlight, specular
    vec4 finish;  // fresnel, saturation, brightness, tint
    vec4 shadow;  // opacity, spread px, offset y px, device pixel ratio
    vec4 sharpRect;  // panel region in the source texture: uv origin, extent
    vec4 blurRect;   // panel region in the blurred texture
    vec4 blurClamp;  // uv min.xy, max.xy inside the blurred region
    float opacity;
};

void main() {
    float dpr = shadow.w;
    vec2 size = panel.xy / dpr;
    vec2 p = quad.xy + position * quad.zw;
    vUV = p / size;
    vLocal = (p - size * 0.5) * dpr;
    gl_Position = mvp * vec4(p, 0.0, 1.0);
}
