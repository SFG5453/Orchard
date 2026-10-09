// Adapted from Kawarp. Copyright (c) 2026 Better Lyrics.
// SPDX-License-Identifier: MIT
// See third_party/kawarp/LICENSE (also bundled at :/licenses/kawarp/LICENSE).
#version 440
layout(location = 0) in vec4 vertex;
layout(location = 1) in vec2 texCoord;
layout(location = 0) out vec2 qt_TexCoord0;
layout(std140, binding = 0) uniform buf {
    mat4 qt_Matrix;
    float qt_Opacity;
    float time;
    float intensity;
    float saturation;
    float blend;
};
void main() {
    qt_TexCoord0 = texCoord;
    gl_Position = qt_Matrix * vertex;
}
