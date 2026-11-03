/*
 * Copyright (C) 2026 SFG545
 * Copyright (C) 2026 Convx Project contributors
 *
 * This file is part of Orchard.
 *
 * Adapted from NavigationTitle (app/src/main/kotlin/com/convx/music/ui/component/NavigationTitle.kt)
 * and DailyDiscoverCard / dailyDiscoverSection
 * (app/src/main/kotlin/com/convx/music/ui/screens/HomeScreen.kt) in Convx v1.5.2,
 * https://github.com/cosmictaserdev-creator/Convx, licensed under the GNU General
 * Public License version 3. It is combined with Orchard under section 13 of the
 * GNU GPL v3 and GNU AGPL v3.
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

internal val HomeGutter = 20.dp
private val HomeSectionGap = 28.dp
private val HomeCardCorner = 20.dp

/** Spotlight entry: the item plus the line shown at the card's foot. */
internal data class DiscoverEntry(val item: CatalogItem, val caption: String)

/**
 * Section header: optional hairline rule, avatar and eyebrow, then the title. A plain "see all"
 * reads as a chevron after the title; Play all and custom labels get a filled pill.
 */
@Composable
internal fun HomeSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    thumbnail: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onPlayAllClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    actionLabel: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) {
            HorizontalDivider(
                thickness = Dp.Hairline,
                color = CanopyColors.Text.copy(alpha = 0.12f),
                modifier = Modifier.padding(start = HomeGutter, end = HomeGutter, top = HomeSectionGap),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (showDivider) 0.dp else HomeSectionGap - 12.dp)
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(horizontal = HomeGutter, vertical = 12.dp),
        ) {
            thumbnail?.invoke()
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f),
            ) {
                label?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = CanopyColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.01).em,
                        color = CanopyColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onClick != null && onPlayAllClick == null && actionLabel == null) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "See all",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            val pill = onPlayAllClick ?: onClick?.takeIf { actionLabel != null }
            if (pill != null) {
                Surface(
                    onClick = pill,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = if (onPlayAllClick != null) 10.dp else 14.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                    ) {
                        if (onPlayAllClick != null) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = CanopyColors.Text, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = actionLabel ?: "Play all",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = CanopyColors.Text,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Multi-browse spotlight: one large card, neighbours shrink toward the edges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverCarousel(
    title: String,
    entries: List<DiscoverEntry>,
    onPlayAll: () -> Unit,
    onClick: (CatalogItem) -> Unit,
) {
    if (entries.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        HomeSectionTitle(title = title, onPlayAllClick = onPlayAll, showDivider = false)
        val state = rememberCarouselState { entries.size }
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = 280.dp,
            itemSpacing = 10.dp,
            contentPadding = PaddingValues(horizontal = HomeGutter),
            modifier = Modifier.fillMaxWidth().height(232.dp),
        ) { index ->
            val entry = entries[index]
            DiscoverCard(
                entry = entry,
                onClick = { onClick(entry.item) },
                modifier = Modifier.maskClip(RoundedCornerShape(HomeCardCorner)),
            )
        }
    }
}

@Composable
private fun DiscoverCard(
    entry: DiscoverEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
    ) {
        ArtworkTile(entry.item.artworkUrl, entry.item.title, Modifier.fillMaxSize(), 0)

        // Collapsed neighbours stay pure artwork; text only fits the focused card.
        if (maxWidth > 200.dp) {
            // Scrim only the foot so the cover art keeps its colour.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f),
                    )
                )
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            ) {
                Text(
                    text = entry.caption.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em),
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = catalogSubtitle(entry.item),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
