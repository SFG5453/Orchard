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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.Box
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
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.components.CatalogCard
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Section header plus a rail of catalog cards. [sectionKey] keys both list items when set. */
internal fun LazyListScope.foldableRail(
    title: String,
    items: List<CatalogItem>,
    onSeeAll: () -> Unit,
    onOpen: (CatalogItem) -> Unit,
    cardWidth: Dp = 160.dp,
    sectionKey: String? = null,
) {
    item(key = sectionKey?.let { "fold_head_$it" }) {
        Box(Modifier.riseIn(distance = 16f)) { FoldableSectionHeader(title = title, onSeeAll = onSeeAll) }
    }
    item(key = sectionKey?.let { "fold_rail_$it" }) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.stableId }) { index, item ->
                CatalogCard(
                    item = item,
                    onClick = { onOpen(item) },
                    modifier = Modifier.width(cardWidth).riseIn(index + 1, fromScale = 0.9f, cascadeOnScroll = true),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun FoldableOfflineBanner(downloadedCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = CanopyColors.Surface,
        border = BorderStroke(1.dp, LocalAccent.current.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Offline Mode",
                    style = MaterialTheme.typography.titleMedium,
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
