// Adapted from LiquidGlass. Copyright (c) 2026 ybouane.
// SPDX-License-Identifier: MIT
// See third_party/liquidglass/LICENSE (also bundled at :/licenses/liquidglass/LICENSE).
#version 440

// Box prefilter down to the blur's working resolution; bilinear taps average 2x2 texels each.
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
    // gl_FragCoord follows texture memory order on every RHI backend, so no Y flip.
    vec2 footprint = inputRect.zw * targetScale;
    vec2 origin = inputRect.xy + (gl_FragCoord.xy - 0.5) * footprint;
    vec2 stride = footprint / taps;
    vec4 sum = vec4(0.0);
    // Constant bounds keep GLSL ES 100 happy; taps never exceeds 8.
    for (int y = 0; y < 8; ++y) {
        if (float(y) >= taps)
            break;
        for (int x = 0; x < 8; ++x) {
            if (float(x) >= taps)
                break;
            sum += tap(origin + (vec2(float(x), float(y)) + 0.5) * stride);
        }
    }
    fragColor = sum / (taps * taps);
}
