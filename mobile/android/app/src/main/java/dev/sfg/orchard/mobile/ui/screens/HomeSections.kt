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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/**
 * Header, horizontal card rail and trailing gap. Item keys are `<keyPrefix>_<stableId>_<index>`;
 * [sectionKey] also keys the three list items so recommendation rails keep their scroll state.
 */
internal fun <T : CatalogItem> LazyListScope.homeRail(
    title: String,
    items: List<T>,
    keyPrefix: String,
    onSeeAll: () -> Unit,
    subtitle: String? = null,
    spacing: Dp = 14.dp,
    sectionKey: String? = null,
    header: (@Composable () -> Unit)? = null,
    card: @Composable (T) -> Unit,
) {
    item(key = sectionKey?.let { "head:$it" }) {
        Box(Modifier.animateItem(fadeInSpec = null).riseIn(distance = 16f)) {
            header?.invoke() ?: HomeSectionHeader(title = title, subtitle = subtitle, onSeeAll = onSeeAll)
        }
    }
    item(key = sectionKey?.let { "rail:$it" }) {
        LazyRow(
            modifier = Modifier.animateItem(fadeInSpec = null),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            itemsIndexed(items, key = { index, item -> "${keyPrefix}_${item.stableId}_$index" }) { index, item ->
                // Cards rise in trailing one another along the rail.
                Box(Modifier.riseIn(index + 1, distance = 20f, fromScale = 0.9f, cascadeOnScroll = true)) {
                    card(item)
                }
            }
        }
    }
    item(key = sectionKey?.let { "gap:$it" }) { Spacer(Modifier.height(4.dp)) }
}

/** Header plus a vertical list of ranked song rows. */
internal fun LazyListScope.homeTrackSection(
    title: String,
    tracks: List<Track>,
    keyPrefix: String,
    onSeeAll: () -> Unit,
    subtitle: String? = null,
    row: @Composable (Int, Track) -> Unit,
) {
    item { Box(Modifier.riseIn(distance = 16f)) { HomeSectionHeader(title = title, subtitle = subtitle, onSeeAll = onSeeAll) } }
    itemsIndexed(tracks, key = { index, track -> "${keyPrefix}_${track.id}_$index" }) { index, track ->
        Box(Modifier.riseIn(index + 1, cascadeOnScroll = true)) { row(index, track) }
    }
    item { Spacer(Modifier.height(4.dp)) }
}

/** Quick picks: pages of [QUICK_PICKS_PER_PAGE] song rows; the next page peeks in from the right. */
internal fun LazyListScope.homeQuickPicks(
    tracks: List<Track>,
    onSeeAll: () -> Unit,
    row: @Composable (Track) -> Unit,
) {
    item(key = "head:quick_picks") {
        Box(Modifier.riseIn(distance = 16f)) {
            HomeSectionHeader(title = "Quick picks", subtitle = "Start a mix from a song", onSeeAll = onSeeAll)
        }
    }
    item(key = "pager:quick_picks") {
        val pages = tracks.chunked(QUICK_PICKS_PER_PAGE)
        val state = rememberPagerState { pages.size }
        HorizontalPager(
            state = state,
            // Rows pad 16dp themselves; 4dp more lines the artwork up with the gutter.
            contentPadding = PaddingValues(start = 4.dp, end = 44.dp),
            verticalAlignment = Alignment.Top,
            key = { pages[it].first().id },
        ) { page ->
            Column { pages[page].forEach { track -> row(track) } }
        }
    }
}

@Composable
internal fun OfflineModeBanner(downloadedCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).riseIn(),
        shape = RoundedCornerShape(14.dp),
        color = CanopyColors.Surface,
        border = BorderStroke(1.dp, LocalAccent.current.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Offline Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = CanopyColors.Text,
                )
                Text(
                    if (downloadedCount > 0)
                        "Showing downloaded music ($downloadedCount ${if (downloadedCount == 1) "song" else "songs"})"
                    else "No internet connection detected",
                    style = MaterialTheme.typography.bodySmall,
                    color = CanopyColors.Muted,
                )
            }
        }
    }
}
