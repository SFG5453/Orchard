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

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlin.math.ceil

/**
 * Material hierarchy and density for frosted architectural glass panes.
 *
 * Glass density is calibrated based on visual layering and what passes behind the pane:
 * - Primary panels sit directly on the artwork backdrop with soft translucent scattering.
 * - Secondary surfaces layer cleanly over panels without muddying contrast.
 * - Chrome floating bars provide robust legibility against rich scrolling content.
 * - Overlays and bottom sheets deliver high focal depth and grounding.
 * - Controls provide tactile affordance with crisp perimeter definition.
 */
enum class GlassTone {
    /** Primary cards, panels, and content containers sitting on the backdrop. */
    PANEL,

    /** Nested sub-panels, list cards, and secondary grouped containers. */
    SECONDARY,

    /** Floating bars, navigation chrome, and mini player where content scrolls underneath. */
    CHROME,

    /** Modal bottom sheets, dialogs, and top-level overlays. */
    OVERLAY,

    /** Interactive chips, search inputs, pills, circular buttons, and compact controls. */
    CONTROL,
}

/**
 * State of the frosted-glass treatment for the whole app.
 *
 * [tint] is the playing cover's dominant colour, so panes belong to what is playing rather than
 * being a fixed grey. It is held as a [State] and read through a property so panes pick up a new
 * cover during the draw phase: publishing an animating colour through a static composition local
 * would instead recompose the entire tree once per frame of the crossfade.
 */
@Stable
class GlassStyle(
    val enabled: Boolean = false,
    private val tintState: State<Color> = mutableStateOf(CanopyColors.Accent),
) {
    val tint: Color get() = tintState.value
}

val LocalGlass = staticCompositionLocalOf { GlassStyle() }

/**
 * The two recordings panes are cut out of.
 *
 * A pane cannot blur a layer it is itself part of — it would be sampling its own output — so
 * there are two, and which one a pane reads is decided by where it sits:
 *
 *  - [wash] holds the artwork backdrop alone. Panels, cards and chips live inside the screen
 *    content, so this is everything that is genuinely behind them.
 *  - [scene] holds the backdrop plus the whole screen. The bottom bar and the mini player are
 *    drawn after it and on top of it, so they blur real album art as the list moves under them.
 *
 * Full-resolution recordings draw the original content unchanged. Two shared quarter-size
 * textures provide softened inputs for every pane, so blur cost does not grow with pane count.
 */
@Stable
class GlassScene internal constructor(
    internal val wash: GraphicsLayer,
    internal val scene: GraphicsLayer,
    internal val washSample: GraphicsLayer,
    internal val sceneSample: GraphicsLayer,
    internal val downscale: Float,
    internal val chromeBlur: Dp,
) {
    internal var washBlurRadius = Float.NaN
    internal var sceneBlurRadius = Float.NaN
    internal var washOrigin = Offset.Zero
    internal var sceneOrigin = Offset.Zero
    internal var washRecorded = false
    internal var sceneRecorded = false
}

val LocalGlassScene = staticCompositionLocalOf<GlassScene?> { null }

@Composable
fun rememberGlassScene(
    downscale: Float = GLASS_DOWNSCALE,
    chromeBlur: Dp = 9.dp,
): GlassScene {
    require(downscale.isFinite() && downscale >= 1f)
    require(chromeBlur.value.isFinite() && chromeBlur > 0.dp)
    val context = LocalGraphicsContext.current
    val scene = remember(context, downscale, chromeBlur) {
        GlassScene(
            context.createGraphicsLayer(), context.createGraphicsLayer(),
            context.createGraphicsLayer(), context.createGraphicsLayer(),
            downscale, chromeBlur,
        )
    }
    DisposableEffect(scene) {
        onDispose {
            context.releaseGraphicsLayer(scene.washSample)
            context.releaseGraphicsLayer(scene.sceneSample)
            context.releaseGraphicsLayer(scene.wash)
            context.releaseGraphicsLayer(scene.scene)
        }
    }
    return scene
}

/**
 * Records the artwork backdrop for panes that sit inside the screen content, and draws it on
 * through unchanged.
 */
fun Modifier.glassWashSource(scene: GlassScene?): Modifier =
    if (scene == null) this else this.recordInto(scene, wash = true)

/** Records the backdrop and the screen together, for the chrome that floats over both. */
fun Modifier.glassSceneSource(scene: GlassScene?): Modifier =
    if (scene == null) this else this.recordInto(scene, wash = false)

private fun Modifier.recordInto(scene: GlassScene, wash: Boolean) = this
    .onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow()
        if (wash) scene.washOrigin = origin else scene.sceneOrigin = origin
    }
    .drawWithContent {
        val layer = if (wash) scene.wash else scene.scene
        layer.record { this@drawWithContent.drawContent() }
        // Resolve the backdrop once per source at reduced resolution. Every pane reuses
        // this GPU texture; never read pixels back to the CPU or blur once per list item.
        val sample = if (wash) scene.washSample else scene.sceneSample
        val radius = (if (wash) 6.dp else scene.chromeBlur).toPx() / scene.downscale
        val previous = if (wash) scene.washBlurRadius else scene.sceneBlurRadius
        if (radius != previous) {
            sample.renderEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
            if (wash) scene.washBlurRadius = radius else scene.sceneBlurRadius = radius
        }
        sample.record(IntSize(
            ceil(size.width / scene.downscale).toInt().coerceAtLeast(1),
            ceil(size.height / scene.downscale).toInt().coerceAtLeast(1),
        )) {
            scale(1f / scene.downscale, pivot = Offset.Zero) { drawLayer(layer) }
        }
        if (wash) scene.washRecorded = true else scene.sceneRecorded = true
        drawLayer(layer)
    }

/**
 * The caller's own fill, unless a frosted pane is about to be painted underneath it instead.
 * Pair with [glassPane][dev.sfg.orchard.mobile.ui.glass.glassPane] on the same surface.
 *
 * [whenGlass] is for surfaces whose fill carried meaning — a selected row, an active device —
 * where clearing it outright would lose the distinction. Give those a translucent wash that
 * still lets the pane through instead.
 */
@Composable
fun glassFill(color: Color, whenGlass: Color = Color.Transparent): Color =
    if (LocalGlass.current.enabled) whenGlass else color

/**
 * Builds the style to provide, without handing the tree a new instance on every recomposition —
 * a static local compares by identity, and a fresh object would rebuild the whole app each time.
 */
@Composable
fun rememberGlassStyle(enabled: Boolean, tint: State<Color>): GlassStyle =
    remember(enabled, tint) { GlassStyle(enabled, tint) }

internal const val GLASS_DOWNSCALE = 4f
