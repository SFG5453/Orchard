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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
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
import kotlin.math.ceil

/**
 * Cuts a frosted pane out of the backdrop recorded by [glassWashSource] / [glassSceneSource].
 *
 * Shared quarter-resolution backdrops are softened once in [GlassScene]. Each pane refracts
 * a crop with one texture lookup per low-resolution pixel on Android 13 and later.
 * A full-resolution finish supplies tint and a crisp rim without blurring the foreground.
 * Android 12 uses the shared softened backdrop and a gradient finish.
 */
@Composable
fun Modifier.glassPane(shape: Shape, tone: GlassTone = GlassTone.PANEL): Modifier {
    val style = LocalGlass.current
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
                    context.createGraphicsLayer().also {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            it.renderEffect = SaturatedBackdropEffect
                        }
                        blur = it
                    }
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
