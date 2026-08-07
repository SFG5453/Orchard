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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.Track
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
            Surface(
                onClick = onAction,
                color = CanopyColors.Surface,
                shape = CircleShape,
                modifier = Modifier.height(32.dp),
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
    val isArtist = item is CatalogItem.Performer
    val cornerRadius = if (isArtist) 999.dp else 14.dp
    val artworkRadius = if (isArtist) 999 else 14
    Column(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
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

/** Expressive top pick hero card with rich gradient scrim and pill badge. */
@Composable
fun TopPickCard(item: CatalogItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(200.dp)
            .aspectRatio(0.85f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
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

/** Badge for explicit songs. */
@Composable
fun ExplicitBadge(modifier: Modifier = Modifier) {
    Surface(
        color = Color.White.copy(alpha = 0.16f),
        shape = RoundedCornerShape(3.dp),
        modifier = modifier,
    ) {
        Text(
            text = "E",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** Expressive track list row with rounded art or track numbers, active row highlight, and popup actions. */
@Composable
fun TrackRow(
    track: Track,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    showArtwork: Boolean = true,
    parentArtist: String = "",
    showDivider: Boolean = false,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onViewAlbum: (() -> Unit)? = null,
    onViewArtist: (() -> Unit)? = null,
    trailingText: String = durationText(track.durationMs),
    highlighted: Boolean = false,
) {
    var popupOpen by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        if (highlighted) LocalAccent.current.copy(alpha = 0.15f) else Color.Transparent,
        label = "TrackRowBg"
    )

    if (popupOpen) {
        TrackActionsPopup(
            track = track,
            onDismiss = { popupOpen = false },
            onPlay = onPlay,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            onDownload = if (!isDownloaded) onDownload else null,
            onRemoveDownload = if (isDownloaded) onRemoveDownload else null,
            onShare = onShare,
            onViewAlbum = onViewAlbum,
            onViewArtist = onViewArtist,
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = onPlay,
            color = bgColor,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showArtwork && trackNumber == null) {
                    Box(contentAlignment = Alignment.Center) {
                        ArtworkTile(track.artworkUrl, "Artwork for ${track.title}", Modifier.size(46.dp), 10)
                        if (highlighted) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "Playing",
                                    tint = LocalAccent.current,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier.width(32.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (highlighted) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Playing",
                                tint = LocalAccent.current,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text(
                                text = (trackNumber ?: 1).toString(),
                                color = Color.White.copy(alpha = 0.45f),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            track.title,
                            color = if (highlighted) LocalAccent.current else Color.White,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (track.explicit) {
                            ExplicitBadge()
                        }
                    }

                    val displayArtist = track.artist.takeIf {
                        it != "Unknown artist" && it.isNotBlank() && (parentArtist.isBlank() || !it.equals(parentArtist, ignoreCase = true))
                    }
                    val displayAlbum = track.album.takeIf { it.isNotBlank() && trackNumber == null }
                    val subtitle = listOfNotNull(displayArtist, displayAlbum).distinct().joinToString(" • ")

                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.60f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = LocalAccent.current,
                        strokeWidth = 2.dp,
                    )
                } else if (isDownloaded) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Downloaded offline",
                        tint = LocalAccent.current.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (trailingText.isNotBlank()) {
                    Text(
                        trailingText,
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
                IconButton(onClick = { popupOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = "Actions for ${track.title}",
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (showArtwork && trackNumber == null) 72.dp else 48.dp, end = 16.dp)
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.08f))
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
                if (isSelected) LocalAccent.current else CanopyColors.Surface,
                label = "ChipBg"
            )
            val textColor by animateColorAsState(
                if (isSelected) Color.Black else CanopyColors.Text,
                label = "ChipText"
            )

            Surface(
                onClick = { onSelect(option) },
                shape = CircleShape,
                color = containerColor,
                modifier = Modifier.height(34.dp)
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
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
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

fun durationText(durationMs: Long): String {
    if (durationMs <= 0) return ""
    val seconds = durationMs / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun catalogSubtitle(item: CatalogItem): String = when (item) {
    is CatalogItem.Song -> item.track.artist
    // On an artist page the artist name is dropped upstream, so the year is
    // usually all that is left; elsewhere it reads as "SZA • 2022".
    is CatalogItem.Record -> listOf(item.album.artist, item.album.year)
        .filter(String::isNotBlank)
        .joinToString(" • ")
    is CatalogItem.Performer -> item.artist.subtitle.ifBlank { "Artist" }
    is CatalogItem.Collection -> item.playlist.author
}

private fun catalogKind(item: CatalogItem): String = when (item) {
    is CatalogItem.Song -> "Song"
    is CatalogItem.Record -> "Album"
    is CatalogItem.Performer -> "Artist"
    is CatalogItem.Collection -> "Playlist"
}
