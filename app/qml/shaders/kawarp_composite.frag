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
};
layout(binding = 1) uniform sampler2D source;

void main() {
    vec3 color = texture(source, qt_TexCoord0).rgb;
    // The shell's #57030704 backing tint, applied before final quantization.
    color = mix(color, vec3(3.0, 7.0, 4.0) / 255.0, 87.0 / 255.0);

    // Stationary, monochrome, one-code-value dither in physical output pixels.
    // Doing this after filtering keeps it fine at fractional/HiDPI scales too.
    float noise = fract(52.9829189 * fract(dot(floor(gl_FragCoord.xy),
                                             vec2(0.06711056, 0.00583715)))) - 0.5;
    color += noise / 255.0;
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0) * qt_Opacity;
}
