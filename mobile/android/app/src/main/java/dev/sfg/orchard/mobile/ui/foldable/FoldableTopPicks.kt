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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.ui.components.RemoteArtwork
import dev.sfg.orchard.mobile.ui.components.rememberArtworkPalette
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow

/** Lead item of a shelf, labelled with the shelf title. */
internal data class TopPick(val eyebrow: String, val item: CatalogItem)

private val PickRadius = 10.dp
private val PickFooterHeight = 78.dp
private val PickGap = 16.dp
private val PickGutter = 24.dp

/** Cards shown per row, plus a 30% peek of the next. */
private fun pickColumns(width: Dp): Int =
    when {
        width >= 1100.dp -> 5
        width >= 800.dp -> 4
        width >= 560.dp -> 3
        else -> 2
    }

/** Top Picks rail: square cover over a footer in the cover's edge colour. */
@Composable
internal fun FoldableTopPicks(picks: List<TopPick>, width: Dp, onOpen: (CatalogItem) -> Unit) {
    val columns = pickColumns(width)
    val cardWidth = maxOf(120.dp, (width - PickGutter * 2 - PickGap * columns) / (columns + 0.3f))
    LazyRow(
        contentPadding = PaddingValues(horizontal = PickGutter),
        horizontalArrangement = Arrangement.spacedBy(PickGap),
    ) {
        itemsIndexed(picks, key = { _, pick -> "pick_${pick.item.stableId}" }) { _, pick ->
            TopPickCard(pick, Modifier.width(cardWidth), onOpen)
        }
    }
}

@Composable
private fun TopPickCard(pick: TopPick, modifier: Modifier, onOpen: (CatalogItem) -> Unit) {
    val item = pick.item
    val footer by animateColorAsState(
        rememberArtworkPalette(item.artworkUrl).deep,
        tween(300),
        label = "TopPickFooter",
    )
    Column(modifier.clip(RoundedCornerShape(PickRadius)).clickable { onOpen(item) }) {
        RemoteArtwork(
            url = item.artworkUrl,
            description = item.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.fillMaxWidth().height(PickFooterHeight).background(footer).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            PickLine(pick.eyebrow, 11.sp, FontWeight.Normal, Color.White.copy(alpha = 0.65f))
            PickLine(item.title, 13.sp, FontWeight.SemiBold, Color(0xFFF5F3EE))
            PickLine(catalogItemSubtitle(item), 12.sp, FontWeight.Normal, Color.White.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun PickLine(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, color: Color) {
    Text(text = text, fontSize = size, fontWeight = weight, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
