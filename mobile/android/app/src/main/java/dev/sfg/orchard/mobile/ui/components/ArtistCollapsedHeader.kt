/*
 * Copyright (C) 2026 SFG545
 * Copyright (C) 2026 Convx Project contributors
 *
 * This file is part of Orchard.
 *
 * Layout adapted from the artist header and sections in ArtistScreen
 * (app/src/main/kotlin/com/convx/music/ui/screens/artist/ArtistScreen.kt) in
 * Convx v1.5.2, https://github.com/cosmictaserdev-creator/Convx, licensed under the
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

import dev.sfg.orchard.mobile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** A section the collapsed artist header can jump to. */
data class ArtistAnchor(val label: String, val index: Int)

/**
 * Artist header once the hero has scrolled away: back, the name, a Play button and a row of
 * section chips. [current] is the anchor the list is showing.
 */
@Composable
fun ArtistCollapsedHeader(
    detail: BrowseDetail,
    palette: ArtworkPalette,
    anchors: List<ArtistAnchor>,
    current: ArtistAnchor?,
    onBack: () -> Unit,
    onPlay: (() -> Unit)?,
    onJump: (ArtistAnchor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(palette.deep.copy(alpha = 0.96f), CanopyColors.Canvas.copy(alpha = 0.96f))))
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back"), tint = CanopyColors.Text)
            }
            ArtistName(
                detail = detail,
                logoHeight = 32.dp,
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                textAlign = TextAlign.Start,
                logoAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f),
            )
            if (onPlay != null) {
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = palette.deep),
                    modifier = Modifier.padding(end = 8.dp).size(44.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${detail.title}")
                }
            }
        }
        if (anchors.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(anchors, key = { _, anchor -> anchor.index }) { _, anchor ->
                    val selected = anchor == current
                    Surface(
                        onClick = { onJump(anchor) },
                        shape = CircleShape,
                        color = if (selected) CanopyColors.Text else Color.White.copy(alpha = 0.08f),
                        contentColor = if (selected) CanopyColors.Chrome else CanopyColors.MutedStrong,
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(
                            text = tr(anchor.label),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 14.dp).wrapContentHeight(),
                        )
                    }
                }
            }
        }
    }
}
