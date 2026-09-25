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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlin.math.ceil

/**
 * Cuts a frosted pane out of the backdrop recorded by [glassWashSource] / [glassSceneSource].
 *
 * Shared quarter-resolution backdrops are softened once in [GlassScene]. Each pane refracts
 * a crop with one texture lookup per low-resolution pixel on Android 13 and later.
 * A full-resolution finish supplies tint and a crisp rim without blurring the foreground.
 * Android 12 uses the shared softened backdrop and a gradient finish.
 *
 * The receiver is returned untouched when the setting is off, so users who never turn it on pay
 * nothing at all — not even a branch during draw.
 */
@Composable
fun Modifier.glassPane(shape: Shape, tone: GlassTone = GlassTone.PANEL): Modifier {
    val style = LocalGlass.current
    if (!style.enabled) return this
    return this then GlassPaneElement(shape, tone, style, LocalGlassScene.current)
}

/**
 * Backdrop processing uses the scene’s reduced resolution.
 * Foreground content and the rim remain at native resolution.
 */
private const val SAMPLE_PADDING = 4f

private data class GlassPaneElement(
    private val shape: Shape,
    private val tone: GlassTone,
    private val style: GlassStyle,
    private val scene: GlassScene?,
) : ModifierNodeElement<GlassPaneNode>() {
    override fun create() = GlassPaneNode(shape, tone, style, scene)

    override fun update(node: GlassPaneNode) = node.update(shape, tone, style, scene)

    override fun InspectorInfo.inspectableProperties() {
        name = "glassPane"
        properties["shape"] = shape
        properties["tone"] = tone
    }
}

private class GlassPaneNode(
    private var shape: Shape,
    private var tone: GlassTone,
    private var style: GlassStyle,
    private var scene: GlassScene?,
) : Modifier.Node(), DrawModifierNode {

    /** So that two panes of the same size do not land on pixel-identical grain. */
    private val seed = SEEDS[System.identityHashCode(this).mod(SEEDS.size)]

    private var blur: GraphicsLayer? = null
    private var lens: LiquidLens? = null

    /** Held rather than re-required on the way out: releasing is not worth a detach-order risk. */
    private var graphics: GraphicsContext? = null

    private var frost: GlassFrostShader? = null
    private var fallback: GlassFrostGradient? = null

    private var outlineSize = Size.Unspecified
    private var outlineDensity = Float.NaN
    private var outlineDirection: LayoutDirection? = null
    private var outline: Outline? = null
    private var clip: Path? = null

    /** Top-left, top-right, bottom-right, bottom-left, in pixels. */
    private val corners = FloatArray(4)

    fun update(shape: Shape, tone: GlassTone, style: GlassStyle, scene: GlassScene?) {
        if (this.tone != tone) {
            // Tone decides the weight of the finish over the shared backdrop.
            fallback = null
        }
        this.shape = shape
        this.tone = tone
        this.style = style
        this.scene = scene
        outlineSize = Size.Unspecified
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        if (size.minDimension > 0.5f) {
            val spec = tone.spec()
            val shapeOutline = outline()
            val blurred = drawBackdrop()
            // Reading the tint here rather than at composition keeps a cover change in the draw
            // phase: panes repaint, nothing recomposes.
            val tint = style.tint
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val painter = frost ?: GlassFrostShader(seed).also { frost = it }
                val path = clip
                if (shapeOutline is Outline.Generic && path != null) {
                    clipPath(path) { painter.draw(this, size, corners, spec, tint, blurred) }
                } else {
                    painter.draw(this, size, corners, spec, tint, blurred)
                }
            } else {
                val painter = fallback ?: GlassFrostGradient(spec).also { fallback = it }
                painter.draw(this, shapeOutline, tint, blurred)
            }
        }
        drawContent()
    }

    /**
     * Crops the shared softened texture beneath this pane into a padded refraction layer.
     * Before a source is recorded, the finish supplies a standalone fallback.
     */
    private fun DrawScope.drawBackdrop(): Boolean {
        val scene = scene ?: return false
        val downscale = scene.downscale
        val chrome = tone == GlassTone.CHROME
        val source = if (chrome) scene.sceneSample else scene.washSample
        if (if (chrome) !scene.sceneRecorded else !scene.washRecorded) return false

        val origin = if (chrome) scene.sceneOrigin else scene.washOrigin
        val here = requireLayoutCoordinates().positionInWindow()
        val offset = Offset(here.x - origin.x, here.y - origin.y)

        val layer =
            blur
                ?: run {
                    val context = requireGraphicsContext()
                    graphics = context
                    context.createGraphicsLayer().also { blur = it }
                }
        val width = ceil(size.width / downscale).toInt().coerceAtLeast(1)
        val height = ceil(size.height / downscale).toInt().coerceAtLeast(1)
        // A padded crop lets refraction sample outside the pane instead of stretching its edge.
        // The full-resolution finish below supplies the antialiased mask and crisp rim.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val painter = lens ?: LiquidLens().also { lens = it }
            layer.renderEffect = painter.effect(size, corners, density, downscale)
        }
        layer.record(IntSize(width + 8, height + 8)) {
            translate(SAMPLE_PADDING - offset.x / downscale,
                SAMPLE_PADDING - offset.y / downscale) { drawLayer(source) }
        }
        val paint: DrawScope.() -> Unit = {
            scale(downscale, pivot = Offset.Zero) {
                translate(-SAMPLE_PADDING, -SAMPLE_PADDING) { drawLayer(layer) }
            }
        }
        val path = clip
        if (path == null) {
            clipRect { paint() }
        } else {
            clipPath(path) { paint() }
        }
        return true
    }

    /**
     * Resolves the caller's [Shape] once per size, into a clip path for the blurred backdrop and
     * the four corner radii the frost's distance field needs. Square corners need no clip and no
     * path. Generic outlines clip both the background and the finish to the caller’s shape.
     */
    private fun DrawScope.outline(): Outline {
        outline
            ?.takeIf { size == outlineSize && layoutDirection == outlineDirection && density == outlineDensity }
            ?.let {
                return it
            }
        val resolved = shape.createOutline(size, layoutDirection, this)
        val limit = size.minDimension / 2f
        if (resolved is Outline.Rounded) {
            val rect = resolved.roundRect
            corners[0] = rect.topLeftCornerRadius.x.coerceIn(0f, limit)
            corners[1] = rect.topRightCornerRadius.x.coerceIn(0f, limit)
            corners[2] = rect.bottomRightCornerRadius.x.coerceIn(0f, limit)
            corners[3] = rect.bottomLeftCornerRadius.x.coerceIn(0f, limit)
        } else {
            corners.fill(0f)
        }
        clip = if (resolved is Outline.Rectangle) null else Path().apply { addOutline(resolved) }
        outline = resolved
        outlineDensity = density
        outlineSize = size
        outlineDirection = layoutDirection
        return resolved
    }

    override fun onDetach() {
        blur?.let { layer -> graphics?.releaseGraphicsLayer(layer) }
        blur = null
        graphics = null
        lens = null
        frost = null
        fallback = null
        outline = null
        outlineSize = Size.Unspecified
        outlineDirection = null
        clip = null
    }
}

/** A single texture lookup per low-resolution fragment; no blur loop or bitmap readback. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LiquidLens {
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

/** The frost over the blurred backdrop: tint, light film, grain, rim and the rounded mask. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class GlassFrostShader(seed: Float) {
    private val shader = RuntimeShader(GLASS_SHADER).apply { setFloatUniform("uSeed", seed) }
    private val brush = ShaderBrush(shader)

    // Uniforms are cheap individually, but they are JNI calls and a pane redraws whenever
    // anything above it in the tree does. Only the ones that actually moved get written.
    private var lastSize = Size.Unspecified
    private val lastCorners = FloatArray(4) { Float.NaN }
    private var lastTint = Color.Unspecified
    private var lastBase = Color.Unspecified
    private var lastFilm = Float.NaN
    private var lastTintMix = Float.NaN
    private var lastUndercoat = Float.NaN

    fun draw(
        scope: DrawScope,
        size: Size,
        corners: FloatArray,
        spec: GlassSpec,
        tint: Color,
        blurred: Boolean,
    ) {
        if (size != lastSize) {
            shader.setFloatUniform("uSize", size.width, size.height)
            lastSize = size
        }
        if (!corners.contentEquals(lastCorners)) {
            shader.setFloatUniform("uRadius", corners[0], corners[1], corners[2], corners[3])
            corners.copyInto(lastCorners)
        }
        if (tint != lastTint) {
            shader.setColorUniform("uTint", tint.toArgb())
            lastTint = tint
        }
        // Standing on a blurred backdrop the frost is a film; standing on nothing it has to be
        // the pane, so it thickens into an opaque body instead of leaving a hole.
        val film = if (blurred) spec.film else spec.solidFilm
        val tintMix = if (blurred) spec.tintMix else spec.solidTintMix
        val base = if (blurred) spec.base else spec.solidBase
        val undercoat = if (blurred) spec.contrastUndercoat else spec.solidUndercoat
        if (film != lastFilm) {
            shader.setFloatUniform("uFilm", film)
            lastFilm = film
        }
        if (tintMix != lastTintMix) {
            shader.setFloatUniform("uTintMix", tintMix)
            lastTintMix = tintMix
        }
        if (undercoat != lastUndercoat) {
            shader.setFloatUniform("uUndercoat", undercoat)
            lastUndercoat = undercoat
        }
        if (base != lastBase) {
            shader.setColorUniform("uBase", base.toArgb())
            lastBase = base
        }
        scope.drawRect(brush)
    }
}

/**
 * Android 12's finish. It still gets the blur — [android.graphics.RenderEffect] goes back to
 * Android 12 — but no grain and no rim that follows the corners, so a pane there is a blur under a
 * tinted film rather than under frost.
 */
private class GlassFrostGradient(private val spec: GlassSpec) {
    private var undercoatBrush: Brush? = null
    private var film: Brush? = null
    private var filmTint = Color.Unspecified
    private var filmBlurred: Boolean? = null

    private val edge =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.22f),
            0.35f to Color.White.copy(alpha = 0.09f),
            0.85f to Color.White.copy(alpha = 0.04f),
            1f to Color.Black.copy(alpha = 0.12f),
        )

    fun draw(scope: DrawScope, outline: Outline, tint: Color, blurred: Boolean) {
        if (tint != filmTint || blurred != filmBlurred) {
            val undercoatWeight = if (blurred) spec.contrastUndercoat else spec.solidUndercoat
            if (undercoatWeight > 0.001f) {
                undercoatBrush =
                    Brush.verticalGradient(
                        0f to CanopyColors.Chrome.copy(alpha = undercoatWeight * 0.85f),
                        1f to CanopyColors.Chrome.copy(alpha = undercoatWeight * 1.15f),
                    )
            } else {
                undercoatBrush = null
            }

            val weight = if (blurred) spec.film else spec.solidFilm
            val mixed =
                lerp(
                    if (blurred) spec.base else spec.solidBase,
                    tint,
                    if (blurred) spec.tintMix else spec.solidTintMix,
                )
            film =
                Brush.verticalGradient(
                    0f to mixed.copy(alpha = (weight * 1.12f).coerceAtMost(1f)),
                    0.35f to mixed.copy(alpha = weight),
                    1f to mixed.copy(alpha = weight * 0.88f),
                )
            filmTint = tint
            filmBlurred = blurred
        }
        undercoatBrush?.let { scope.drawOutline(outline, brush = it) }
        scope.drawOutline(outline, brush = film ?: return)
        scope.drawOutline(outline, brush = edge, style = Stroke(width = 1f))
    }
}

/**
 * Per-tone constants. The `solid` variants are the ones used when no backdrop was captured, where
 * the frost has to carry the whole pane on its own.
 */
private class GlassSpec(
    val base: Color,
    val film: Float,
    val tintMix: Float,
    val contrastUndercoat: Float,
    val solidBase: Color,
    val solidFilm: Float,
    val solidTintMix: Float,
    val solidUndercoat: Float,
)

private val PanelSpec =
    GlassSpec(
        base = CanopyColors.Glass,
        film = 0.14f,
        tintMix = 0.06f,
        contrastUndercoat = 0.12f,
        solidBase = CanopyColors.Surface,
        solidFilm = 0.94f,
        solidTintMix = 0.03f,
        solidUndercoat = 0.0f,
    )

private val SecondarySpec =
    GlassSpec(
        base = CanopyColors.Glass,
        film = 0.18f,
        tintMix = 0.05f,
        contrastUndercoat = 0.18f,
        solidBase = CanopyColors.SurfaceHover,
        solidFilm = 0.95f,
        solidTintMix = 0.02f,
        solidUndercoat = 0.0f,
    )

private val ChromeSpec =
    GlassSpec(
        base = CanopyColors.GlassChrome,
        film = 0.18f,
        tintMix = 0.07f,
        contrastUndercoat = 0.18f,
        solidBase = CanopyColors.Chrome,
        solidFilm = 0.98f,
        solidTintMix = 0.04f,
        solidUndercoat = 0.0f,
    )

private val OverlaySpec =
    GlassSpec(
        base = CanopyColors.GlassChrome,
        film = 0.74f,
        tintMix = 0.05f,
        contrastUndercoat = 0.58f,
        solidBase = CanopyColors.Chrome,
        solidFilm = 0.98f,
        solidTintMix = 0.03f,
        solidUndercoat = 0.0f,
    )

private val ControlSpec =
    GlassSpec(
        base = CanopyColors.Glass,
        film = 0.16f,
        tintMix = 0.05f,
        contrastUndercoat = 0.14f,
        solidBase = CanopyColors.Surface,
        solidFilm = 0.92f,
        solidTintMix = 0.03f,
        solidUndercoat = 0.0f,
    )

private fun GlassTone.spec(): GlassSpec =
    when (this) {
        GlassTone.PANEL -> PanelSpec
        GlassTone.SECONDARY -> SecondarySpec
        GlassTone.CHROME -> ChromeSpec
        GlassTone.OVERLAY -> OverlaySpec
        GlassTone.CONTROL -> ControlSpec
    }

private val SEEDS = floatArrayOf(0f, 137.5f, 311.7f, 523.9f, 719.3f, 941.1f)

/**
 * One pass: rounded mask, contrast undercoat, tinted film, velvety satin micro-frost,
 * restrained etched rim scattering, and dimensional grounding, all laid over the blurred
 * backdrop the pane has already drawn.
 *
 * Returns premultiplied alpha, which is what Skia expects back from a runtime shader.
 */
private const val GLASS_SHADER = """
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
