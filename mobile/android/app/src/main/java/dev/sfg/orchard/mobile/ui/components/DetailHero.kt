/*
 * Copyright (C) 2026 SFG545
 * Copyright (C) 2026 Convx Project contributors
 *
 * This file is part of Orchard.
 *
 * Adapted from the hero headers of AlbumScreen
 * (app/src/main/kotlin/com/convx/music/ui/screens/AlbumScreen.kt), ArtistScreen and
 * FeaturedReleaseCard (app/src/main/kotlin/com/convx/music/ui/screens/artist/ArtistScreen.kt),
 * OnlinePlaylistScreen (app/src/main/kotlin/com/convx/music/ui/screens/playlist/OnlinePlaylistScreen.kt)
 * and ExpandableText (app/src/main/kotlin/com/convx/music/ui/component/ExpandableText.kt)
 * in Convx v1.5.2, https://github.com/cosmictaserdev-creator/Convx, licensed under the
 * GNU General Public License version 3. It is combined with Orchard under section 13
 * of the GNU GPL v3 and GNU AGPL v3.
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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import dev.sfg.orchard.mobile.ui.motion.bounceClickable
import dev.sfg.orchard.mobile.ui.motion.popOnChange
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.motion.riseIn
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Side margin for hero titles and controls. */
internal val HeroGutter = 32.dp

/** Caps the square on tablets so the title still lands above the fold. */
private val HeroArtMaxHeight = 560.dp

/** How far the title block rides up into the faded tail of the art. */
private val HeroTitleOverlap = 96.dp

/**
 * Detail page header: edge-to-edge square art that dissolves into the page tint,
 * with [content] (titles, controls) stacked from its faded tail downward.
 */
@Composable
fun DetailHeroFrame(
    artworkUrl: String,
    description: String,
    modifier: Modifier = Modifier,
    animatedArtworkUrl: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    // Art settles from a slight zoom as the page opens, like a camera finding focus.
    var settled by rememberSaveable { mutableStateOf(false) }
    val settle = remember { Animatable(if (settled) 1f else 1.12f) }
    LaunchedEffect(Unit) {
        if (settled) return@LaunchedEffect
        settle.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        settled = true
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val artHeight = min(maxWidth, HeroArtMaxHeight)
        Box(
            Modifier
                .fillMaxWidth()
                .height(artHeight)
                // Offscreen so DstIn masks the art alone, not the page tint behind it.
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
                            startY = size.height * 0.4f,
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            ArtworkTile(url = artworkUrl, description = description, modifier = Modifier.fillMaxSize(), radius = 0)
            if (animatedArtworkUrl.isNotBlank()) {
                AnimatedArtworkVideo(url = animatedArtworkUrl, active = true, modifier = Modifier.fillMaxSize())
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = artHeight - HeroTitleOverlap, bottom = 16.dp)
                .riseIn(index = 2, distance = 36f),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** Frosted circle used for the secondary hero actions. */
@Composable
fun HeroCircleButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = CanopyColors.Text,
    size: Dp = 48.dp,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = CircleShape,
        interactionSource = source,
        modifier = modifier.pressScale(source, 0.86f).size(size).glassPane(CircleShape, GlassTone.CONTROL),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                // Toggles (save, follow) swap the icon; give the swap a little pop.
                modifier = Modifier.size(22.dp).popOnChange(icon, peak = 1.45f),
            )
        }
    }
}

/**
 * Hero transport: two 48dp circles flanking a 72dp play disc. [sideDrop] lowers the
 * flanking buttons so they sit grounded beside the larger disc.
 */
@Composable
fun HeroPlayRow(
    onPlay: () -> Unit,
    playFill: Color,
    playIconTint: Color,
    modifier: Modifier = Modifier,
    playEnabled: Boolean = true,
    sideDrop: Dp = 0.dp,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = HeroGutter),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.offset(y = sideDrop).riseIn(3, fromScale = 0.6f)) { leading() }
        val playSource = remember { MutableInteractionSource() }
        // Three soft rings ripple out of the disc once the page lands, then it sits still.
        val halo = remember { Animatable(0f) }
        var pulsed by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(playEnabled) {
            if (!playEnabled || pulsed) return@LaunchedEffect
            delay(650)
            repeat(3) {
                halo.snapTo(0f)
                halo.animateTo(1f, tween(1100, easing = LinearOutSlowInEasing))
            }
            halo.snapTo(0f)
            pulsed = true
        }
        Surface(
            onClick = onPlay,
            enabled = playEnabled,
            shape = CircleShape,
            color = if (playEnabled) playFill else playFill.copy(alpha = 0.3f),
            interactionSource = playSource,
            modifier = Modifier
                .riseIn(2, fromScale = 0.4f)
                .drawBehind {
                    val p = halo.value
                    if (p > 0f) {
                        drawCircle(
                            color = playFill.copy(alpha = 0.45f * (1f - p)),
                            radius = size.minDimension / 2f * (1f + 0.55f * p),
                        )
                    }
                }
                .pressScale(playSource, 0.86f)
                .size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = playIconTint,
                    // The play glyph is visually left-heavy; nudge it to the optical centre.
                    modifier = Modifier.size(36.dp).offset(x = 2.dp),
                )
            }
        }
        Box(Modifier.offset(y = sideDrop).riseIn(4, fromScale = 0.6f)) { trailing() }
    }
}

/** Frosted capsule for counts and badges under the hero title. */
@Composable
fun HeroChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .glassPane(CircleShape, GlassTone.CONTROL)
            .then(if (onClick != null) Modifier.bounceClickable(CircleShape, 0.92f, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = CanopyColors.Text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = CanopyColors.Text,
            maxLines = 1,
        )
    }
}

/** "About" block whose body expands in place past three lines. */
@Composable
fun HeroAbout(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by rememberSaveable(text) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .animateContentSize(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = CanopyColors.Text,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = text.replace(Regex("\\s+"), " ").trim(),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = CanopyColors.Text.copy(alpha = 0.72f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            modifier = Modifier.clickable(enabled = overflows || expanded) { expanded = !expanded },
        )
        if (overflows || expanded) {
            Text(
                text = if (expanded) "Less" else "More",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = CanopyColors.Text,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/** Newest release, pulled out of the discography rail into its own card. */
@Composable
fun FeaturedReleaseCard(
    item: CatalogItem.Record,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.2f),
        interactionSource = source,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).riseIn().pressScale(source, 0.96f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtworkTile(item.artworkUrl, item.title, Modifier.size(80.dp), radius = 8)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.album.year.ifBlank { "Latest release" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text.copy(alpha = 0.6f),
                    maxLines = 1,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = CanopyColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.album.artist.ifBlank { "Album" },
                    style = MaterialTheme.typography.bodySmall,
                    color = CanopyColors.Text.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = CanopyColors.Text,
                modifier = Modifier.padding(horizontal = 8.dp).size(24.dp),
            )
        }
    }
}

/** "Album • 2022 • 12 tracks • 45m": the one-line summary under a collection title. */
fun heroInfoLine(kindLabel: String, year: String, trackCount: Int, totalMs: Long): String = buildString {
    append(kindLabel)
    if (year.isNotBlank()) append(" • $year")
    if (trackCount > 0) append(" • $trackCount ${if (trackCount == 1) "track" else "tracks"}")
    val totalMinutes = totalMs / 60_000
    if (totalMinutes > 0) {
        val hours = totalMinutes / 60
        append(if (hours > 0) " • ${hours}h ${totalMinutes % 60}m" else " • ${totalMinutes}m")
    }
}
