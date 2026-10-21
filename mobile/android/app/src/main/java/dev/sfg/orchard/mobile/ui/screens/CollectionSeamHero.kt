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

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.sfg.orchard.mobile.ui.components.AnimatedArtworkVideo
import dev.sfg.orchard.mobile.ui.components.ArtworkPalette
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.motion.riseIn

/** Hero art for a collection page. [tallUrl] is Apple's portrait cover and wins over [animatedUrl] on phones. */
data class HeroArt(
    val tallUrl: String = "",
    val animatedUrl: String = "",
) {
    /** Width over height of the phone hero's crop. */
    val phoneAspect: Float get() = if (tallUrl.isNotBlank()) TALL_ASPECT else 1f

    /** The still the phone hero shows. */
    fun phoneStill(coverUrl: String): String = tallUrl.ifBlank { coverUrl }
}

/** Apple's portrait covers are 3:4. */
private const val TALL_ASPECT = 0.75f

/** Band fill and the text colours drawn on it. */
internal data class SeamColors(val band: Color, val text: Color, val secondaryText: Color)

/** The band is the page's own tint, taken from the art's bottom edge, so band and list read as one surface. */
internal fun seamColors(palette: ArtworkPalette): SeamColors =
    SeamColors(palette.deep, Color.White, Color.White.copy(alpha = 0.72f))

/** [preferred] when it holds 3:1 against [background], else [fallback]. */
internal fun legibleOn(background: Color, preferred: Color, fallback: Color): Color {
    val light = maxOf(background.luminance(), preferred.luminance()) + 0.05f
    val dark = minOf(background.luminance(), preferred.luminance()) + 0.05f
    return if (light / dark >= 3f) preferred else fallback
}

private val SeamArtMaxHeight = 680.dp
private val PlayDiscSize = 72.dp

/** Share of the art's height that fades into the band; short enough to keep the cover crisp. */
private const val SeamFade = 0.22f

/**
 * Phone collection header: full-bleed art whose last strip fades into a solid colour band, with
 * the play disc straddling the seam at the end edge.
 */
@Composable
internal fun SeamHeroFrame(
    coverUrl: String,
    description: String,
    art: HeroArt,
    colors: SeamColors,
    playFill: Color,
    playIconTint: Color,
    playEnabled: Boolean,
    onPlay: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SeamArt(coverUrl, description, art)
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.band)
                    .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 10.dp),
            ) {
                // Clear of the disc, which overhangs the band's top end corner.
                Column(Modifier.padding(end = PlayDiscSize + 8.dp).riseIn(index = 1), content = details)
                Row(
                    modifier = Modifier.offset(x = (-10).dp).padding(top = 6.dp).riseIn(index = 3),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            PlayDisc(
                fill = playFill,
                iconTint = playIconTint,
                enabled = playEnabled,
                onClick = onPlay,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 20.dp)
                    .offset(y = -PlayDiscSize / 2),
            )
        }
    }
}

@Composable
private fun SeamArt(coverUrl: String, description: String, art: HeroArt) {
    // Art settles from a slight zoom as the page opens.
    var settled by rememberSaveable { mutableStateOf(false) }
    val settle = remember { Animatable(if (settled) 1f else 1.08f) }
    LaunchedEffect(Unit) {
        if (settled) return@LaunchedEffect
        settle.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        settled = true
    }
    val tall = art.tallUrl.isNotBlank()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = min(maxWidth / art.phoneAspect, SeamArtMaxHeight)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                // Offscreen so DstIn masks the art alone; the page tint shows through the tail.
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    scaleX = settle.value
                    scaleY = settle.value
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height * (1f - SeamFade),
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            ArtworkTile(
                url = art.phoneStill(coverUrl),
                description = description,
                modifier = Modifier.fillMaxSize(),
                radius = 0,
            )
            if (!tall && art.animatedUrl.isNotBlank()) {
                AnimatedArtworkVideo(url = art.animatedUrl, active = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PlayDisc(
    fill: Color,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) fill else fill.copy(alpha = 0.3f),
        shadowElevation = 8.dp,
        interactionSource = source,
        modifier = modifier.riseIn(2, fromScale = 0.4f).pressScale(source, 0.86f).size(PlayDiscSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "Play",
                tint = iconTint,
                // The play glyph is visually left-heavy; nudge it to the optical centre.
                modifier = Modifier.size(34.dp).offset(x = 2.dp),
            )
        }
    }
}

/** One 44dp icon in the band's action row. */
@Composable
internal fun SeamAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}
