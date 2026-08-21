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

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlin.math.roundToInt

/**
 * Cuts a frosted pane out of the backdrop recorded by [glassWashSource] / [glassSceneSource].
 *
 * The pane is a real blur, not a tinted film over what is behind it: the region of the recording
 * that sits under the pane is redrawn into a small offscreen layer, blurred there, and scaled back
 * up. Blurring at [DOWNSCALE]× less than screen resolution is what makes it affordable — a nav bar
 * spanning a 1080px screen blurs 270px of width instead, and the result is a blur, so there is no
 * detail in it left to lose on the way back up. The blur and the saturation lift ride in one
 * [android.graphics.RenderEffect] chain, evaluated by the GPU as the layer is composited.
 *
 * AGSL then draws the frost itself over that blurred base — tint, grain, rim light and the rounded
 * mask in a single pass, with no offscreen buffer and no extra layers for clipping or a border.
 * Android 12 has [android.graphics.RenderEffect] but no [RuntimeShader], so it gets the blur with a
 * plainer gradient finish.
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
 * How much smaller than the screen the blur is computed at. Four is the point where the cost has
 * dropped sixteenfold and upscaling still shows nothing, because everything above the blur's own
 * cutoff frequency is gone by then anyway.
 */
private const val DOWNSCALE = 4f

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
    private var blurRadius = Float.NaN

    /** Held rather than re-required on the way out: releasing is not worth a detach-order risk. */
    private var graphics: GraphicsContext? = null

    private var frost: GlassFrostShader? = null
    private var fallback: GlassFrostGradient? = null

    private var outlineSize = Size.Unspecified
    private var outlineDirection: LayoutDirection? = null
    private var outline: Outline? = null
    private var clip: Path? = null

    /** Top-left, top-right, bottom-right, bottom-left, in pixels. */
    private val corners = FloatArray(4)

    fun update(shape: Shape, tone: GlassTone, style: GlassStyle, scene: GlassScene?) {
        if (this.tone != tone) {
            // Tone decides the blur radius and the weight of the frost over it.
            blurRadius = Float.NaN
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
            val blurred = drawBackdrop(spec)
            // Reading the tint here rather than at composition keeps a cover change in the draw
            // phase: panes repaint, nothing recomposes.
            val tint = style.tint
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val painter = frost ?: GlassFrostShader(seed).also { frost = it }
                painter.draw(this, size, corners, spec, tint, blurred)
            } else {
                val painter = fallback ?: GlassFrostGradient(spec).also { fallback = it }
                painter.draw(this, shapeOutline, tint, blurred)
            }
        }
        drawContent()
    }

    /**
     * Redraws the slice of the recording that sits under this pane into a downscaled layer,
     * blurred. Returns false before the first frame has been recorded, or on a screen with no
     * source at all, and the frost then falls back to standing on its own.
     */
    private fun DrawScope.drawBackdrop(spec: GlassSpec): Boolean {
        val scene = scene ?: return false
        val chrome = tone == GlassTone.CHROME
        val source = if (chrome) scene.scene else scene.wash
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
        val radius = spec.blur.toPx() / DOWNSCALE
        if (radius != blurRadius) {
            layer.renderEffect = frostedBackdropEffect(radius)
            blurRadius = radius
        }

        val width = (size.width / DOWNSCALE).roundToInt().coerceAtLeast(1)
        val height = (size.height / DOWNSCALE).roundToInt().coerceAtLeast(1)
        layer.record(IntSize(width, height)) {
            scale(1f / DOWNSCALE, pivot = Offset.Zero) {
                translate(-offset.x, -offset.y) { drawLayer(source) }
            }
        }

        val path = clip
        if (path == null) {
            scale(DOWNSCALE, pivot = Offset.Zero) { drawLayer(layer) }
        } else {
            clipPath(path) { scale(DOWNSCALE, pivot = Offset.Zero) { drawLayer(layer) } }
        }
        return true
    }

    /**
     * Resolves the caller's [Shape] once per size, into a clip path for the blurred backdrop and
     * the four corner radii the frost's distance field needs. Square corners need no clip and no
     * path, which is the common case for a bar; the shader squares off anything that is not a
     * rounded rectangle, so a caller using one of those is expected to be clipping already.
     */
    private fun DrawScope.outline(): Outline {
        outline
            ?.takeIf { size == outlineSize && layoutDirection == outlineDirection }
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
        outlineSize = size
        outlineDirection = layoutDirection
        return resolved
    }

    override fun onDetach() {
        blur?.let { layer -> graphics?.releaseGraphicsLayer(layer) }
        blur = null
        graphics = null
        blurRadius = Float.NaN
        frost = null
        fallback = null
        outline = null
        clip = null
    }
}

/**
 * Blur plus a saturation lift, in one chain. Real frosted glass scatters rather than dims, and a
 * plain blur of a dark app reads as a smudge; pushing saturation back up is what makes the cover
 * behind a pane still look like the cover.
 */
private fun frostedBackdropEffect(radius: Float) =
    android.graphics.RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.7f) }),
            android.graphics.RenderEffect.createBlurEffect(
                radius,
                radius,
                android.graphics.Shader.TileMode.CLAMP,
            ),
        )
        .asComposeRenderEffect()

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
    val blur: Dp,
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
        blur = 28.dp,
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
        blur = 22.dp,
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
        blur = 36.dp,
        base = CanopyColors.GlassChrome,
        film = 0.44f,
        tintMix = 0.07f,
        contrastUndercoat = 0.28f,
        solidBase = CanopyColors.Chrome,
        solidFilm = 0.98f,
        solidTintMix = 0.04f,
        solidUndercoat = 0.0f,
    )

private val OverlaySpec =
    GlassSpec(
        blur = 42.dp,
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
        blur = 18.dp,
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
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
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
    body = body + satinFrost(coord + uSeed) * 0.020;

    // 4. Architectural etched edge treatment:
    // Crisp 1.0px inner perimeter hairline (strictly inside shape: d in [-1.2, 0.0])
    float innerHairline = smoothstep(-1.2, -0.1, d) * mask;
    float topGlint = clamp(1.0 - uv.y * 1.9, 0.0, 1.0) * (0.58 + 0.42 * clamp(1.0 - uv.x * 1.3, 0.0, 1.0));
    float rimHighlight = innerHairline * (0.13 + 0.32 * topGlint);

    // Soft subsurface perimeter light scattering (0 to 5px inside edge)
    float innerScatter = smoothstep(-5.0, 0.0, d) * (ambientTop * 0.032 + ambientLeft * 0.016);

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
