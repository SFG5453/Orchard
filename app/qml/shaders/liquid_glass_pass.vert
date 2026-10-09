// Adapted from LiquidGlass. Copyright (c) 2026 ybouane.
// SPDX-License-Identifier: MIT
// See third_party/liquidglass/LICENSE (also bundled at :/licenses/liquidglass/LICENSE).
#version 440

// Unit quad stretched over the whole offscreen target.
layout(location = 0) in vec2 position;

void main() {
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
