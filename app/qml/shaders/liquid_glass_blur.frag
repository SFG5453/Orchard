// Adapted from LiquidGlass. Copyright (c) 2026 ybouane.
// SPDX-License-Identifier: MIT
// See third_party/liquidglass/LICENSE (also bundled at :/licenses/liquidglass/LICENSE).
#version 440

// One axis of a separable Gaussian; weights and linear-sampled offsets come from the CPU.
layout(location = 0) out vec4 fragColor;
layout(std140, binding = 0) uniform buf {
    vec4 inputRect;    // uv origin and extent of the region this pass reads
    vec4 inputClamp;   // uv min.xy, max.xy; keeps taps out of unused texture slack
    vec2 texel;        // 1 / input texture size
    vec2 targetScale;  // 1 / used target size in texels
    vec2 direction;    // blur axis
    float taps;        // box: taps per axis; blur: linear-sampled pairs per side
    float center;      // blur: centre weight
    vec4 kernel[8];    // blur: x = offset in texels, y = weight
};
layout(binding = 1) uniform sampler2D source;

vec4 tap(vec2 uv) {
    return texture(source, clamp(uv, inputClamp.xy, inputClamp.zw));
}

void main() {
    vec2 uv = inputRect.xy + gl_FragCoord.xy * targetScale * inputRect.zw;
    vec2 axis = direction * texel;
    vec4 sum = tap(uv) * center;
    for (int i = 0; i < 8; ++i) {
        if (float(i) >= taps)
            break;
        vec2 o = axis * kernel[i].x;
        sum += (tap(uv + o) + tap(uv - o)) * kernel[i].y;
    }
    fragColor = sum;
}
