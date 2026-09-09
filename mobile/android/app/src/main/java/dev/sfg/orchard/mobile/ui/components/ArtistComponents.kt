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

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassFill
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Cinematic edge-to-edge hero header for artists. */
@Composable
fun ArtistHero(
    detail: BrowseDetail,
    palette: ArtworkPalette,
    onBack: () -> Unit,
    onPlayAll: (List<Track>, String) -> Unit,
    onShuffle: (List<Track>, String) -> Unit,
    shuffleAvailable: Boolean,
    onSave: (BrowseDetail) -> Unit,
    isSaved: Boolean,
    onOpenBio: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(450.dp),
    ) {
        ArtworkTile(detail.artworkUrl, detail.title, Modifier.fillMaxSize(), 0)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        0.5f to Color.Transparent,
                        0.75f to palette.deep.copy(alpha = 0.6f),
                        1f to palette.deep,
                    ),
                ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailBackButton(onBack)
            if (detail.description.isNotBlank()) {
                Surface(
                    onClick = onOpenBio,
                    color = glassFill(CanopyColors.Surface),
                    shape = CircleShape,
                    modifier = Modifier.padding(end = 8.dp).size(40.dp).glassPane(CircleShape, GlassTone.CONTROL),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Info, contentDescription = "About", tint = CanopyColors.Text, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = { onShuffle(detail.tracks, detail.title) },
                    enabled = detail.tracks.isNotEmpty() && shuffleAvailable,
                    color = glassFill(CanopyColors.Surface),
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp).glassPane(CircleShape, GlassTone.CONTROL),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = LocalAccent.current, modifier = Modifier.size(20.dp))
                    }
                }
                Surface(
                    onClick = { onPlayAll(detail.tracks, detail.title) },
                    enabled = detail.tracks.isNotEmpty(),
                    color = LocalAccent.current,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play all", tint = Color.Black, modifier = Modifier.size(32.dp))
                    }
                }
                Surface(
                    onClick = { onSave(detail) },
                    color = glassFill(CanopyColors.Surface),
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp).glassPane(CircleShape, GlassTone.CONTROL),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSaved) Icons.Filled.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isSaved) "Unfollow artist" else "Follow artist",
                            tint = CanopyColors.Favorite,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LatestReleaseCard(
    item: CatalogItem.Record,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp)
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                ArtworkTile(item.artworkUrl, item.title, Modifier.fillMaxSize(), 10)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "LATEST RELEASE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                    color = CanopyColors.Muted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = CanopyColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp).size(24.dp)
            )
        }
    }
}

/** Interactive bottom sheet displaying artist or album description without cluttering the main page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailDescriptionBottomSheet(detail: BrowseDetail, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CanopyColors.Chrome,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CanopyColors.Muted.copy(alpha = 0.4f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "About ${detail.title}",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = CanopyColors.Text,
            )
            if (detail.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detail.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAccent.current,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = detail.description,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                color = CanopyColors.Muted,
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
fun ArtistBioBottomSheet(detail: BrowseDetail, onDismiss: () -> Unit) = DetailDescriptionBottomSheet(detail, onDismiss)

@Composable
fun DetailBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier.padding(start = 8.dp, top = 8.dp)) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = CanopyColors.Text)
    }
}
