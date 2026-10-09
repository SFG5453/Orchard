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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.motion.bounceClickable
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.motion.riseIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.scale
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    OrchardSectionHeader(title, action = action, onAction = onAction)
}

@Composable
fun OrchardSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            ),
            color = CanopyColors.Text,
        )
        if (action != null && onAction != null) {
            val source = remember { MutableInteractionSource() }
            Surface(
                onClick = onAction,
                color = Color.Transparent,
                shape = CircleShape,
                interactionSource = source,
                modifier = Modifier.height(32.dp).pressScale(source, 0.9f).glassPane(CircleShape, GlassTone.CONTROL),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = action,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = LocalAccent.current,
                    )
                }
            }
        }
    }
}

/** Expressive rail card with soft rounded artwork (14dp or circular for artists) and clear typography. */
@Composable
fun CatalogCard(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (item is CatalogItem.Category) {
        CategoryCard(item = item, onClick = onClick, modifier = modifier)
        return
    }
    val isArtist = item is CatalogItem.Performer
    val cornerRadius = if (isArtist) 999.dp else 14.dp
    val artworkRadius = if (isArtist) 999 else 14
    Column(
        modifier = modifier
            .width(140.dp)
            .bounceClickable(RoundedCornerShape(14.dp), pressedScale = 0.93f, onClick = onClick),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(cornerRadius))
        ) {
            ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), artworkRadius)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = CanopyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (item is CatalogItem.Song && item.track.explicit) {
                ExplicitBadge()
            }
        }
        Text(
            catalogSubtitle(item),
            style = MaterialTheme.typography.bodyMedium,
            color = CanopyColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Expressive category / genre / mood card with left colored accent stripe. */
@Composable
fun CategoryCard(
    item: CatalogItem.Category,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val stripeColor = item.stripeColor?.let { Color(it.toInt()) } ?: LocalAccent.current

    Surface(
        modifier = modifier
            .height(52.dp)
            .glassPane(shape, GlassTone.CONTROL)
            .bounceClickable(shape, pressedScale = 0.94f, onClick = onClick),
        color = Color.Transparent,
        shape = shape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(stripeColor),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = CanopyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Expressive top pick hero card with rich gradient scrim and pill badge. */
@Composable
fun TopPickCard(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(200.dp)
            .aspectRatio(0.85f)
            .bounceClickable(RoundedCornerShape(18.dp), pressedScale = 0.95f, onClick = onClick),
    ) {
        ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 0)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.85f),
                    ),
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Surface(
                color = LocalAccent.current.copy(alpha = 0.85f),
                shape = CircleShape,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = catalogKind(item).uppercase(),
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            if (item is CatalogItem.Song && item.track.explicit) {
                ExplicitBadge(modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                catalogSubtitle(item),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.80f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Filter pill chip group inspired by SimpMusic ChipGroup. */
@Composable
fun <T> OrchardFilterChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { index ->
            val option = options[index]
            val isSelected = option == selected
            val containerColor by animateColorAsState(
                if (isSelected) LocalAccent.current else Color.Transparent,
                label = "ChipBg"
            )
            val textColor by animateColorAsState(
                if (isSelected) Color.Black else CanopyColors.Text,
                label = "ChipText"
            )
            // Selected chip swells a touch so the choice lands with some weight.
            val chipScale by animateFloatAsState(
                if (isSelected) 1.06f else 1f,
                spring(dampingRatio = 0.45f, stiffness = 500f),
                label = "ChipScale",
            )
            val source = remember { MutableInteractionSource() }

            Surface(
                onClick = { onSelect(option) },
                shape = CircleShape,
                color = containerColor,
                interactionSource = source,
                // The selected chip is a solid accent fill, which is what makes it selected.
                modifier = Modifier
                    .riseIn(index + 1, fromScale = 0.8f, cascadeOnScroll = true)
                    .scale(chipScale)
                    .pressScale(source, 0.9f)
                    .height(34.dp)
                    .then(
                        if (isSelected) Modifier
                        else Modifier.glassPane(CircleShape, GlassTone.CONTROL),
                    ),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
fun MessagePanel(title: String, message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = Color.Transparent,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .riseIn()
            .glassPane(shape)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = CanopyColors.Text)
            Spacer(Modifier.height(8.dp))
            Text(message, color = CanopyColors.Muted, style = MaterialTheme.typography.bodyLarge)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    onClick = onAction,
                    color = LocalAccent.current,
                    shape = CircleShape,
                    modifier = Modifier.height(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 18.dp)) {
                        Text(actionLabel, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

private fun catalogSubtitle(item: CatalogItem): String = when (item) {
    is CatalogItem.Song -> item.track.artist
    // On an artist page the artist name is dropped upstream, so the year is
    // usually all that is left; elsewhere it reads as "SZA • 2022".
    is CatalogItem.Record -> albumSubtitle(item.album)
    is CatalogItem.Performer -> item.artist.subtitle.ifBlank { "Artist" }
    is CatalogItem.Collection -> item.playlist.author
    is CatalogItem.Category -> ""
}

/**
 * "SZA • 2017".
 *
 * Albums saved before the artist field was populated correctly still hold the whole browse
 * line — "Album • 2017" — in [Album.artist], which used to render as "Album • 2017 • 2017"
 * once the year was appended. Rather than migrate the cache, the parts that were never an
 * artist name are dropped on the way out.
 */
private fun albumSubtitle(album: Album): String {
    val parts = album.artist.split("•").map(String::trim).filter(String::isNotBlank)
    val artist = parts.filterNot { it.lowercase() in ALBUM_KIND_WORDS || YEAR_ONLY.matches(it) }
    // A year stranded in the artist line is still the album's year if nothing else has it.
    val year = album.year.ifBlank { parts.lastOrNull(YEAR_ONLY::matches).orEmpty() }
    return (artist + year).filter(String::isNotBlank).distinct().joinToString(" • ")
}

private val ALBUM_KIND_WORDS = setOf("album", "single", "ep", "compilation")
private val YEAR_ONLY = Regex("""\d{4}""")

private fun catalogKind(item: CatalogItem): String = when (item) {
    is CatalogItem.Song -> "Song"
    is CatalogItem.Record -> "Album"
    is CatalogItem.Performer -> "Artist"
    is CatalogItem.Collection -> "Playlist"
    is CatalogItem.Category -> "Category"
}
