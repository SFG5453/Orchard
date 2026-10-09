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

package dev.sfg.orchard.mobile.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect

/** A single texture lookup per low-resolution fragment; no blur loop or bitmap readback. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class LiquidLens {
    private val shader = RuntimeShader(LIQUID_LENS_SHADER)
    private var lastSize = Size.Unspecified
    private var lastDensity = Float.NaN
    private var lastDownscale = Float.NaN
    private val lastCorners = FloatArray(4) { Float.NaN }
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun effect(size: Size, corners: FloatArray, density: Float, downscale: Float): androidx.compose.ui.graphics.RenderEffect {
        if (size != lastSize || density != lastDensity || downscale != lastDownscale || !corners.contentEquals(lastCorners)) {
            shader.setFloatUniform("extent", size.width / downscale, size.height / downscale)
            shader.setFloatUniform("radii", corners[0] / downscale, corners[1] / downscale,
                corners[2] / downscale, corners[3] / downscale)
            shader.setFloatUniform("bevel", (10f * density / downscale).coerceAtLeast(1f))
            // RenderEffect snapshots uniforms: replace only when geometry changes.
            cached = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
                .asComposeRenderEffect()
            lastSize = size
            lastDensity = density
            lastDownscale = downscale
            corners.copyInto(lastCorners)
        }
        return checkNotNull(cached)
    }
}

private const val LIQUID_LENS_SHADER = """
uniform shader backdrop;
uniform float2 extent;
uniform float4 radii;
uniform float bevel;
half4 main(float2 coord) {
    float2 p = coord - float2(4.0) - extent * 0.5;
    float r = p.x > 0.0 ? (p.y > 0.0 ? radii.z : radii.y)
                         : (p.y > 0.0 ? radii.w : radii.x);
    float2 q = abs(p) - extent * 0.5 + r;
    float2 outer = max(q, float2(0.0));
    float len = length(outer);
    float distance = min(max(q.x, q.y), 0.0) + len - r;
    float2 normal = len > 0.001 ? outer / max(len, 0.001)
        : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    normal *= sign(p);
    float edge = clamp(1.0 + distance / bevel, 0.0, 1.0);
    // Curved edge bends the transmitted image inward, leaving the body undistorted.
    float2 sampleAt = coord - normal * (edge * edge * min(bevel * 0.65, 3.0));
    half4 color = backdrop.eval(sampleAt);
    half luminance = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
    color.rgb = clamp(mix(half3(luminance), color.rgb, 1.15), 0.0, color.a);
    return color;
}
"""

internal val SEEDS = floatArrayOf(0f, 137.5f, 311.7f, 523.9f, 719.3f, 941.1f)

/**
 * One pass: rounded mask, contrast undercoat, tinted film, velvety satin micro-frost,
 * restrained etched rim scattering, and dimensional grounding, all laid over the blurred
 * backdrop the pane has already drawn.
 *
 * Returns premultiplied alpha, which is what Skia expects back from a runtime shader.
 */
internal const val GLASS_SHADER = """
uniform float2 uSize;
// Top-left, top-right, bottom-right, bottom-left radii in pixels. A bar flush with the bottom of
// the screen needs its lower corners left square, so one radius for the whole pane will not do.
uniform float4 uRadius;
uniform float uSeed;
uniform float uFilm;
uniform float uTintMix;
uniform float uUndercoat;
layout(color) uniform half4 uBase;
layout(color) uniform half4 uTint;

float roundedBox(float2 p, float2 halfExtent, float4 radii) {
    float2 pair = (p.x > 0.0) ? float2(radii.y, radii.z) : float2(radii.x, radii.w);
    float r = (p.y > 0.0) ? pair.y : pair.x;
    float2 q = abs(p) - halfExtent + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

float hash(float2 p) {
    return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
}

// Silky satin micro-frost noise (authentic etched glass finish, eliminates banding)
float satinFrost(float2 p) {
    float n1 = hash(p) - 0.5;
    float n2 = hash(p * 2.13 + 17.37) - 0.5;
    return n1 * 0.65 + n2 * 0.35;
}

half4 main(float2 coord) {
    float2 halfExtent = uSize * 0.5;
    float d = roundedBox(coord - halfExtent, halfExtent, uRadius);
    // Sub-pixel antialiased boundary mask
    float mask = clamp(0.5 - d, 0.0, 1.0);
    if (mask <= 0.0) {
        return half4(0.0);
    }

    float2 uv = coord / max(uSize, float2(1.0));

    // Studio ambient lighting: soft top-down architectural light diffusion
    float ambientTop = clamp(1.0 - uv.y * 1.30, 0.0, 1.0);
    float ambientLeft = clamp(1.0 - uv.x * 1.20, 0.0, 1.0);
    float lightField = clamp(ambientTop * 0.72 + ambientLeft * 0.28, 0.0, 1.0);

    // 1. Contrast floor undercoat: calm dark base protecting text legibility over bright art
    float3 undercoatColor = float3(0.043, 0.055, 0.071);
    float undercoatAlpha = uUndercoat;

    // 2. Frosted body tinting with subtle luminance transmission
    float3 baseColor = float3(uBase.rgb);
    float3 tintColor = float3(uTint.rgb);
    float3 frostColor = mix(baseColor, tintColor, uTintMix * (0.35 + 0.65 * lightField));
    // Soft diffuse top sheen (diffuse architectural reflection, not mirror/gloss)
    frostColor = frostColor + lightField * 0.048;
    float frostAlpha = uFilm * (0.88 + 0.18 * lightField);

    // Composite undercoat with frost body
    float3 body = mix(undercoatColor, frostColor, frostAlpha / max(undercoatAlpha + frostAlpha, 0.001));
    float alpha = clamp(undercoatAlpha + frostAlpha * (1.0 - undercoatAlpha * 0.40), 0.0, 1.0);

    // 3. Tactile satin micro-frost (breaks up gradient banding, adds velvety etched texture)
    body = body + (hash(coord + uSeed) - 0.5) * 0.004;

    // 4. Architectural etched edge treatment:
    // Crisp 1.0px inner perimeter hairline (strictly inside shape: d in [-1.2, 0.0])
    float innerHairline = smoothstep(-1.2, -0.1, d) * mask;
    float topGlint = clamp(1.0 - uv.y * 1.9, 0.0, 1.0) * (0.58 + 0.42 * clamp(1.0 - uv.x * 1.3, 0.0, 1.0));
    float rimHighlight = innerHairline * (0.22 + 0.65 * topGlint);

    // Soft subsurface perimeter light scattering (0 to 5px inside edge)
    float innerScatter = smoothstep(-8.0, 0.0, d) * (ambientTop * 0.10 + ambientLeft * 0.05);

    float totalEdge = rimHighlight + innerScatter;
    body = body + totalEdge;
    alpha = min(alpha + totalEdge * 0.48, 1.0);

    // 5. Dimensional grounding: subtle underside bevel contact occlusion
    float bottomOcclusion = innerHairline * clamp((uv.y - 0.72) / 0.28, 0.0, 1.0);
    float rightOcclusion = innerHairline * clamp((uv.x - 0.82) / 0.18, 0.0, 1.0);
    float underside = (bottomOcclusion * 0.72 + rightOcclusion * 0.28);
    body = body - underside * 0.070;
    alpha = min(alpha + underside * 0.06, 1.0);

    alpha = clamp(alpha * mask, 0.0, 1.0);
    return half4(half3(clamp(body, 0.0, 1.0) * alpha), half(alpha));
}
"""
