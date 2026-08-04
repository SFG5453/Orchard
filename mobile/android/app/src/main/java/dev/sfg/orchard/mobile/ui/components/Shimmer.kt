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

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/**
 * Animated shimmer modifier matching SimpMusic shimmer effect.
 */
fun Modifier.shimmer(): Modifier = composed {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "OrchardShimmer")
    val startOffsetX by transition.animateFloat(
        initialValue = -2 * size.width.toFloat(),
        targetValue = 2 * size.width.toFloat(),
        animationSpec = infiniteRepeatable(animation = tween(1000)),
        label = "ShimmerOffset",
    )

    background(
        brush = Brush.linearGradient(
            colors = listOf(
                CanopyColors.ShimmerBackground,
                CanopyColors.ShimmerLine,
                CanopyColors.ShimmerBackground,
            ),
            start = Offset(startOffsetX, 0f),
            end = Offset(startOffsetX + size.width.toFloat(), size.height.toFloat()),
        ),
    ).onGloballyPositioned {
        size = it.size
    }
}

@Composable
fun CatalogCardShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(140.dp)) {
        Box(
            Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CanopyColors.ShimmerBackground)
                .shimmer()
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(110.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CanopyColors.ShimmerBackground)
                .shimmer()
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(80.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CanopyColors.ShimmerBackground)
                .shimmer()
        )
    }
}

@Composable
fun TrackRowShimmer(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CanopyColors.ShimmerBackground)
                .shimmer()
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .width(180.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CanopyColors.ShimmerBackground)
                    .shimmer()
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .width(120.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CanopyColors.ShimmerBackground)
                    .shimmer()
            )
        }
    }
}

@Composable
fun HomeSectionShimmer(title: String = "Loading...") {
    Column(Modifier.padding(vertical = 12.dp)) {
        Box(
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .width(120.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CanopyColors.ShimmerBackground)
                .shimmer()
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(userScrollEnabled = false, modifier = Modifier.fillMaxWidth()) {
            items(5) {
                CatalogCardShimmer(Modifier.padding(start = 16.dp))
            }
        }
    }
}
