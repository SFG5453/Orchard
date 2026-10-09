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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
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
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.motion.bounceClickable
import dev.sfg.orchard.mobile.ui.motion.pressScale
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import androidx.compose.foundation.interaction.MutableInteractionSource

internal val HomeCardWidth = 156.dp

/**
 * Spotify-style 2-column x 3-row compact grid for quick access favorites.
 */
@Composable
internal fun SpotifyQuickGrid(
    items: List<QuickGridItem>,
) {
    val rows = remember(items) { items.chunked(2) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEachIndexed { col, item ->
                    // Diagonal cascade: each tile trails its upper-left neighbour.
                    Box(modifier = Modifier.weight(1f).riseIn(rowIndex * 2 + col)) {
                        QuickGridCard(
                            item = item,
                        )
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Individual Spotify-style quick grid item card.
 */
@Composable
private fun QuickGridCard(
    item: QuickGridItem,
) {
    val shape = RoundedCornerShape(10.dp)
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = item.onClick,
        shape = shape,
        color = Color.White.copy(alpha = 0.07f),
        interactionSource = source,
        modifier = Modifier
            .pressScale(source, 0.95f)
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .then(
                        if (item.gradient != null) Modifier.background(item.gradient)
                        else Modifier
                    )
            ) {
                if (item.artworkUrl.isNotBlank()) {
                    ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 0)
                } else if (item.icon != null) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            item.icon,
                            contentDescription = item.title,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                ),
                color = CanopyColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
    }
}

/**
 * Artwork card for "Made for you" section with bottom-right floating play button.
 */
@Composable
internal fun GlassSquircleCard(
    item: CatalogItem,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .width(HomeCardWidth)
            .aspectRatio(1f)
            .bounceClickable(shape, pressedScale = 0.93f, onClick = onClick)
    ) {
        ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 0)

        // Gradient scrim at bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.40f to Color.Black.copy(alpha = 0.30f),
                        1f to Color.Black.copy(alpha = 0.85f),
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp),
            )

            // Mini circular play button
            val playSource = remember { MutableInteractionSource() }
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 3.dp,
                interactionSource = playSource,
                modifier = Modifier.size(32.dp).pressScale(playSource, 0.8f),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play ${item.title}",
                        tint = Color(0xFF101318),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Vertical rounded card for "Recently played" section.
 */
@Composable
internal fun GlassRecentlyPlayedCard(
    item: CatalogItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(HomeCardWidth)
            .bounceClickable(RoundedCornerShape(24.dp), pressedScale = 0.93f, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
        ) {
            ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 24)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = (-0.2).sp,
            ),
            color = CanopyColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = catalogSubtitle(item),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = CanopyColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Circular artist avatar card matching ArchiveTune "Keep listening" section.
 */
@Composable
internal fun CircularArtistCard(item: CatalogItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .bounceClickable(RoundedCornerShape(16.dp), 0.9f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
        ) {
            ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 999)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            ),
            color = CanopyColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = catalogSubtitle(item).ifBlank { "Artist" },
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = CanopyColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
