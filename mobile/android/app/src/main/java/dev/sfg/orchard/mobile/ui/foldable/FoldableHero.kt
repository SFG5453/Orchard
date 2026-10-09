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

package dev.sfg.orchard.mobile.ui.foldable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import java.util.Calendar

@Composable
internal fun FoldableFullBleedHero(
    items: List<CatalogItem>,
    auth: AuthState,
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onPlay: (CatalogItem) -> Unit,
    onClick: (CatalogItem) -> Unit,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val displayName =
        when (auth) {
            is AuthState.SignedIn -> auth.displayName.ifBlank { "Listener" }
            else -> "Guest"
        }
    val avatarUrl =
        when (auth) {
            is AuthState.SignedIn -> auth.avatarUrl
            else -> ""
        }

    Box(modifier = Modifier.fillMaxWidth().height(410.dp)) {
        // 1. Full-bleed background pager
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            Box(modifier = Modifier.fillMaxSize().clickable { onClick(item) }) {
                RemoteArtwork(
                    url = item.artworkUrl,
                    description = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                // Top scrim for status bar, greeting, and category chip readability
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(180.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.86f),
                                    0.50f to Color.Black.copy(alpha = 0.48f),
                                    1f to Color.Transparent,
                                )
                            )
                )

                // Bottom scrim dissolving smoothly into CanopyColors.Chrome
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.35f to Color.Transparent,
                                    0.65f to Color.Black.copy(alpha = 0.55f),
                                    0.86f to CanopyColors.Chrome.copy(alpha = 0.92f),
                                    1f to CanopyColors.Chrome,
                                )
                            )
                )
            }
        }

        // 2. Overlaid Floating Content
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: Greeting + Search + Avatar + Category Filter Pills
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$greeting, $displayName",
                        style =
                            MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 26.sp,
                            ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Spacer(Modifier.width(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Frosted Search Pill
                        Surface(
                            onClick = onSearch,
                            color = Color.Black.copy(alpha = 0.40f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            shape = CircleShape,
                            modifier = Modifier.width(220.dp).height(40.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = "Search",
                                    tint = Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Search music, albums...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.70f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // User Avatar
                        Surface(
                            onClick = onProfile,
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.40f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (avatarUrl.isNotBlank()) {
                                ArtworkTile(
                                    url = avatarUrl,
                                    description = displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    radius = 999,
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Person,
                                        contentDescription = "User Avatar",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        val isSelected = selectedCategory == category
                        Surface(
                            onClick = { onSelectCategory(category) },
                            shape = CircleShape,
                            color =
                                if (isSelected) Color.White else Color.Black.copy(alpha = 0.40f),
                            border =
                                if (isSelected) null
                                else BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = category,
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            fontWeight =
                                                if (isSelected) FontWeight.Bold
                                                else FontWeight.Medium,
                                            fontSize = 13.sp,
                                        ),
                                    color =
                                        if (isSelected) Color.Black
                                        else Color.White.copy(alpha = 0.90f),
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Hero Meta and Action Controls
            val currentItem = items.getOrNull(pagerState.currentPage) ?: items.first()
            val badge =
                when (currentItem) {
                    is CatalogItem.Song -> "SONG"
                    is CatalogItem.Collection -> "PLAYLIST"
                    is CatalogItem.Record -> "ALBUM"
                    is CatalogItem.Performer -> "ARTIST"
                    is CatalogItem.Category -> "FEATURED"
                }
            val subtitle = catalogItemSubtitle(currentItem)

            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Surface(
                    color = CanopyColors.Surface.copy(alpha = 0.85f),
                    shape = CircleShape,
                ) {
                    Text(
                        text = badge,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = currentItem.title,
                    style =
                        TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = subtitle.ifBlank { "Featured Collection" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = { onPlay(currentItem) },
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 6.dp,
                            modifier = Modifier.height(42.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Play",
                                    style =
                                        MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                        ),
                                    color = Color.Black,
                                )
                            }
                        }

                        // Secondary Details Button
                        Surface(
                            onClick = { onClick(currentItem) },
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.40f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            modifier = Modifier.height(42.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = "Details",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Details",
                                    style =
                                        MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                        ),
                                    color = Color.White,
                                )
                            }
                        }
                    }

                    // Pager Indicators
                    if (items.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(items.size) { idx ->
                                val isSelected = pagerState.currentPage == idx
                                Box(
                                    modifier =
                                        Modifier.height(5.dp)
                                            .width(if (isSelected) 18.dp else 5.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) Color.White
                                                else Color.White.copy(alpha = 0.35f)
                                            )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
